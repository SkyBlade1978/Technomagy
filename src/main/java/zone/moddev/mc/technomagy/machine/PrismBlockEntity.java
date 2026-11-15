package zone.moddev.mc.technomagy.machine;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;

import zone.moddev.mc.technomagy.mana.ManaPacket;
import zone.moddev.mc.technomagy.mana.ManaType;

/**
 * A prism's state (D20, D26, D27, D89). Its ports go by the order links were made: the first link
 * into it is port 0, the second port 1; likewise for links out of it.
 *
 * <p>
 * <b>Combine mode.</b> Exactly two links may arrive: port 0 feeds the <b>primary</b> slot and port
 * 1 the <b>secondary</b> (D52). Each slot holds one packet. When both are full and the previous
 * merge has left, the prism merges them, primary first, and sends the result over its one outgoing
 * link.
 *
 * <p>
 * <b>Split mode.</b> One link may arrive, feeding one input slot. The prism undoes the packet's
 * most recent merge (D26, D37): the primary part leaves on outgoing link 0, the secondary on link
 * 1. A Raw input splits into all six flavors in one step (D27), each leaving on the outgoing link
 * whose filter is that flavor. Any other single-element input has nothing to undo and passes
 * through unchanged on link 0. A prism ignores its outgoing links' filters except for that Raw
 * routing (D88).
 *
 * <p>
 * A split waits until the prism has at least one outgoing link. A part with no link to leave on (a
 * flavor no link is filtered to, or a secondary with no link 1) sparks and sputters away at the
 * prism instead of blocking the split (D89), so unused outputs show as untapped potential. Outputs
 * are keyed by outgoing link index; removing an outgoing link releases the output bound to it and
 * shifts later ones down, as the links shift.
 *
 * <p>
 * Changing the prism's mode releases whatever it holds (see {@link #modeChanged}), since slots
 * filled under one mode mean nothing under the other.
 *
 * <p>
 * Not thread-safe; see {@link ManaNodeBlockEntity}.
 */
public final class PrismBlockEntity extends ManaNodeBlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Height of the spark burst above the block centre: the glass bar, model pixel 11 of 16. */
    private static final double SPARK_HEIGHT = 3.0 / 16.0;

    /** Electric sparks per sputtered part (D89). */
    private static final int SPARKS_PER_PART = 6;

    /** The sputter crackle's volume; quiet, since a prism can sputter every other pulse. */
    private static final float CRACKLE_VOLUME = 0.35f;

    /** The crackle's base pitch, raised from fire's so it reads as a sharp electric snap. */
    private static final float CRACKLE_PITCH = 1.6f;

    /** One output bound to an outgoing link, as saved. */
    private record Output(int index, ManaPacket packet) {
        static final Codec<Output> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                Codec.intRange(0, ManaType.FLAVORS.size() - 1).fieldOf("link")
                    .forGetter(Output::index),
                ManaPacket.CODEC.fieldOf("packet").forGetter(Output::packet))
                .apply(instance, Output::new));
    }

    private static final Codec<List<Output>> OUTPUTS = Output.CODEC.listOf();

    private Held primary;
    private Held secondary;
    private Held input;
    private final TreeMap<Integer, Held> outputs = new TreeMap<>();
    private boolean reportedDepth;

    /**
     * Creates the block entity.
     *
     * @param pos
     *            its position
     * @param state
     *            its block state
     */
    public PrismBlockEntity(BlockPos pos, BlockState state) {
        super(MachineContent.PRISM_ENTITY.get(), pos, state);
    }

    /** The current mode, from the block state. */
    private PrismBlock.Mode mode() {
        return getBlockState().getValue(PrismBlock.MODE);
    }

    /**
     * Fills the slot the source's port feeds, if it is empty. A packet offered with no link has no
     * port and is refused.
     */
    @Override
    boolean accept(ManaPacket packet, BlockPos from, long now, boolean simulate) {
        int port = from == null ? -1 : incoming().indexOf(from);
        Held arriving = new Held(packet, now);
        if (mode() == PrismBlock.Mode.COMBINE) {
            if (port == 0 && primary == null) {
                if (!simulate) {
                    primary = arriving;
                    setChanged();
                }
                return true;
            }
            if (port == 1 && secondary == null) {
                if (!simulate) {
                    secondary = arriving;
                    setChanged();
                }
                return true;
            }
            return false;
        }
        if (port == 0 && input == null) {
            if (!simulate) {
                input = arriving;
                setChanged();
            }
            return true;
        }
        return false;
    }

    /** Merges or splits, as the class describes. */
    @Override
    protected void process(ServerLevel level, long now) {
        if (!outputs.isEmpty()) {
            return;
        }
        if (mode() == PrismBlock.Mode.COMBINE) {
            combine(now);
        } else {
            split(level, now);
        }
    }

    /** Merges the two slots, primary first, once both hold packets from earlier pulses. */
    private void combine(long now) {
        if (primary == null || secondary == null || primary.arrived() >= now
            || secondary.arrived() >= now || outgoing().isEmpty()) {
            return;
        }
        if (!ManaPacket.canMerge(primary.packet(), secondary.packet())) {
            if (!reportedDepth) {
                reportedDepth = true;
                LOGGER.warn("Prism at {} cannot merge: the result would nest more than {} merges,"
                    + " the most a packet can save. Its inputs wait.", worldPosition,
                    ManaPacket.MAX_DEPTH);
            }
            return;
        }
        reportedDepth = false;
        outputs.put(0, new Held(ManaPacket.merge(primary.packet(), secondary.packet()), now));
        primary = null;
        secondary = null;
        setChanged();
    }

    /**
     * Splits the input once the prism has at least one outgoing link (D89). Each part leaves on the
     * link it is bound to; a part with no such link sputters away at the prism (see
     * {@link #sputter}), showing the player the potential they are not using.
     *
     * @param level
     *            the level, for the sputter effect
     * @param now
     *            the pulse's game time
     */
    private void split(ServerLevel level, long now) {
        List<ManaLink> out = outgoing();
        if (input == null || input.arrived() >= now || out.isEmpty()) {
            return;
        }
        ManaPacket packet = input.packet();
        List<ManaPacket> lost = new ArrayList<>();
        if (packet.isElement() && packet.stream().element() == ManaType.RAW) {
            Map<ManaType, Integer> routes = new EnumMap<>(ManaType.class);
            for (int i = 0; i < out.size(); i++) {
                int index = i;
                out.get(i).filter().pureType().filter(type -> type != ManaType.RAW)
                    .ifPresent(type -> routes.putIfAbsent(type, index));
            }
            int units = packet.stream().units();
            for (ManaType flavor : ManaType.FLAVORS) {
                ManaPacket part = ManaPacket.leaf(flavor, units);
                Integer route = routes.get(flavor);
                if (route != null) {
                    outputs.put(route, new Held(part, now));
                } else {
                    lost.add(part);
                }
            }
        } else if (packet.isElement()) {
            outputs.put(0, new Held(packet, now));
        } else {
            List<ManaPacket> parts = packet.split();
            outputs.put(0, new Held(parts.get(0), now));
            if (out.size() >= 2) {
                outputs.put(1, new Held(parts.get(1), now));
            } else {
                lost.add(parts.get(1));
            }
        }
        input = null;
        setChanged();
        if (!lost.isEmpty()) {
            sputter(level, lost);
        }
    }

    /**
     * Spends split parts that had no outgoing link to leave on (D89): their flavors' particles
     * burst from the prism as when a node breaks, with electric sparks and a quiet crackle, so an
     * unused output is visible and audible rather than silently lost.
     *
     * @param level
     *            the level
     * @param lost
     *            the parts with no link; never empty
     */
    private void sputter(ServerLevel level, List<ManaPacket> lost) {
        spill(level, lost);
        Vec3 bar = Vec3.atCenterOf(worldPosition).add(0, SPARK_HEIGHT, 0);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, bar.x, bar.y, bar.z,
            SPARKS_PER_PART * lost.size(), 0.35, 0.2, 0.35, 0.15);
        level.playSound(null, worldPosition, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS,
            CRACKLE_VOLUME, CRACKLE_PITCH + level.random.nextFloat() * 0.2f);
    }

    /** Sends each output over the outgoing link it is bound to, ignoring that link's filter. */
    @Override
    protected void send(ServerLevel level, long now) {
        List<ManaLink> out = outgoing();
        Iterator<Map.Entry<Integer, Held>> pending = outputs.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<Integer, Held> entry = pending.next();
            Held held = entry.getValue();
            if (entry.getKey() >= out.size() || held.arrived() >= now) {
                continue;
            }
            ManaLink link = out.get(entry.getKey());
            if (deliver(level, link, held.packet(), now, true)) {
                if (!deliver(level, link, held.packet(), now, false)) {
                    throw new IllegalStateException(
                        "Target of " + link + " accepted a packet when simulated only");
                }
                pending.remove();
            }
        }
    }

    /** Releases the output bound to the removed link; later outputs shift down with their links. */
    @Override
    protected void outgoingRemoved(ServerLevel level, int index) {
        Held orphan = outputs.remove(index);
        TreeMap<Integer, Held> shifted = new TreeMap<>();
        outputs.forEach((key, held) -> shifted.put(key > index ? key - 1 : key, held));
        outputs.clear();
        outputs.putAll(shifted);
        if (orphan != null) {
            spill(level, List.of(orphan.packet()));
        }
        setChanged();
    }

    /**
     * Called after the block's mode switches: releases everything held, since slots filled under
     * one mode mean nothing under the other.
     *
     * @param level
     *            the level
     */
    void modeChanged(ServerLevel level) {
        release(level);
    }

    @Override
    protected List<ManaPacket> held() {
        List<ManaPacket> packets = new ArrayList<>();
        for (Held held : new Held[]{primary, secondary, input}) {
            if (held != null) {
                packets.add(held.packet());
            }
        }
        outputs.values().forEach(held -> packets.add(held.packet()));
        return packets;
    }

    @Override
    protected void clearHeld() {
        primary = null;
        secondary = null;
        input = null;
        outputs.clear();
    }

    /** Two links into a combining prism, one into a splitting one. */
    @Override
    int maxIncoming() {
        return mode() == PrismBlock.Mode.COMBINE ? 2 : 1;
    }

    /** One link out of a combining prism; six out of a splitting one, enough for Raw. */
    @Override
    int maxOutgoing() {
        return mode() == PrismBlock.Mode.COMBINE ? 1 : ManaType.FLAVORS.size();
    }

    /** Saves the slots and the outputs with their link indices. */
    @Override
    protected void saveHeld(CompoundTag tag) {
        save(tag, "primary", primary);
        save(tag, "secondary", secondary);
        save(tag, "input", input);
        List<Output> saved = new ArrayList<>();
        outputs.forEach((index, held) -> saved.add(new Output(index, held.packet())));
        tag.put("outputs", OUTPUTS.encodeStart(NbtOps.INSTANCE, saved).getOrThrow());
    }

    /** Loads the slots and outputs; loaded packets may move on the first pulse. */
    @Override
    protected void loadHeld(CompoundTag tag) {
        primary = load(tag, "primary");
        secondary = load(tag, "secondary");
        input = load(tag, "input");
        outputs.clear();
        for (Output output : decodeList(OUTPUTS, tag.get("outputs"), "prism outputs")) {
            outputs.put(output.index(), new Held(output.packet(), Long.MIN_VALUE));
        }
    }

    /** Writes one optional slot. */
    private static void save(CompoundTag tag, String key, Held held) {
        if (held != null) {
            tag.put(key, ManaPacket.CODEC.encodeStart(NbtOps.INSTANCE, held.packet())
                .getOrThrow());
        }
    }

    /** Reads one optional slot, reporting and dropping a packet that fails to decode. */
    private Held load(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return null;
        }
        Optional<ManaPacket> packet = ManaPacket.CODEC.parse(NbtOps.INSTANCE, tag.get(key))
            .resultOrPartial(error -> LOGGER.error(
                "Unreadable {} packet in prism at {}: {}. Dropping it.", key, worldPosition,
                error));
        return packet.map(value -> new Held(value, Long.MIN_VALUE)).orElse(null);
    }
}
