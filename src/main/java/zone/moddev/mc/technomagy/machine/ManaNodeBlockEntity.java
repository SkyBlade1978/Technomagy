package zone.moddev.mc.technomagy.machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3f;

import org.slf4j.Logger;

import zone.moddev.mc.technomagy.mana.IManaHandler;
import zone.moddev.mc.technomagy.mana.Mana;
import zone.moddev.mc.technomagy.mana.ManaColors;
import zone.moddev.mc.technomagy.mana.ManaPacket;
import zone.moddev.mc.technomagy.mana.ManaType;

/**
 * The shared half of a link node, a node director or a prism: the links it owns, the order its
 * incoming links were made in, the transfer pulse, client sync, and what happens when it breaks
 * (D87-D92).
 *
 * <p>
 * <b>Ownership.</b> A node owns its outgoing links, and also the <b>pull</b> links that arrive from
 * plain mana handlers, which have no block entity to own them (D87). It also records the sources of
 * every link arriving at it, in the order those links were made, because a prism's ports go by that
 * order (D89). {@link ManaLinks} keeps the two sides consistent when links are made or removed; a
 * pulse repairs anything a break in an unloaded chunk left behind.
 *
 * <p>
 * <b>The pulse.</b> Every {@link MachineConfig#PULSE_INTERVAL} ticks of game time, each node:
 * <ol>
 * <li>drops links whose other end is no longer a valid endpoint, and prunes stale incoming
 * sources;</li>
 * <li>re-checks each owned link's line of sight, pausing obstructed links (D87);</li>
 * <li>draws packets over its pull links from plain handlers;</li>
 * <li>{@linkplain #process transforms} what it holds (prisms merge or split);</li>
 * <li>{@linkplain #send sends} packets over its outgoing links, at most each link's rate;</li>
 * <li>tells clients if any beam's visible state changed (D92).</li>
 * </ol>
 * Every node pulses on the same game tick, in no particular order. So that a packet moves exactly
 * one link per pulse whatever that order is, each held packet records the tick it arrived, and a
 * node only processes or sends packets that arrived before the current pulse.
 *
 * <p>
 * <b>Client side.</b> Clients hold only {@link BeamState}s from the update tag, never links or
 * packets; the renderer draws those.
 *
 * <p>
 * Not thread-safe: the server thread owns the server instance, the client thread the client one.
 */
public abstract class ManaNodeBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Codec<List<ManaLink>> LINKS = ManaLink.CODEC.listOf();
    private static final Codec<List<BlockPos>> POSITIONS = BlockPos.CODEC.listOf();
    private static final Codec<List<BeamState>> BEAMS = BeamState.CODEC.listOf();

    private final List<ManaLink> links = new ArrayList<>();
    private final List<BlockPos> incoming = new ArrayList<>();
    private final IManaHandler handler = new NodeHandler();
    private List<BeamState> synced = List.of();
    private List<BeamState> clientBeams = List.of();

    /**
     * Creates the block entity.
     *
     * @param type
     *            its registered type
     * @param pos
     *            its position
     * @param state
     *            its block state
     */
    protected ManaNodeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ---- Subclass contract -------------------------------------------------------------------

    /**
     * Offers a packet arriving over a link (D89).
     *
     * @param packet
     *            the packet
     * @param from
     *            the link's source, which decides a prism's port; null for a packet offered through
     *            the capability with no link
     * @param now
     *            the pulse's game time, recorded as the packet's arrival
     * @param simulate
     *            if true, only report whether it would be accepted
     * @return true if accepted (or acceptable, when simulating)
     */
    abstract boolean accept(ManaPacket packet, BlockPos from, long now, boolean simulate);

    /**
     * Transforms held packets during a pulse; prisms merge or split here. Only packets that arrived
     * before {@code now} may be processed.
     *
     * @param level
     *            the level, for any effects processing shows
     * @param now
     *            the pulse's game time
     */
    protected abstract void process(ServerLevel level, long now);

    /**
     * Sends held packets over the outgoing links during a pulse, using {@link #deliver}. Only
     * packets that arrived before {@code now} may be sent.
     *
     * @param level
     *            the level
     * @param now
     *            the pulse's game time
     */
    protected abstract void send(ServerLevel level, long now);

    /**
     * Every packet this node holds, for release when it breaks (D87).
     *
     * @return the packets; empty if none
     */
    protected abstract List<ManaPacket> held();

    /** Forgets every held packet, after they have been released. */
    protected abstract void clearHeld();

    /**
     * Called after an outgoing link is removed, so a prism can drop output bound to it.
     *
     * @param level
     *            the level
     * @param index
     *            the removed link's index among the outgoing links before removal
     */
    protected void outgoingRemoved(ServerLevel level, int index) {
    }

    /**
     * How many links may arrive at this node (D89).
     *
     * @return the limit
     */
    abstract int maxIncoming();

    /**
     * How many links may leave this node (D89).
     *
     * @return the limit
     */
    abstract int maxOutgoing();

    /**
     * Saves held packets.
     *
     * @param tag
     *            the tag to write into
     */
    protected abstract void saveHeld(CompoundTag tag);

    /**
     * Loads held packets, reporting and dropping any that fail to decode.
     *
     * @param tag
     *            the saved tag
     */
    protected abstract void loadHeld(CompoundTag tag);

    // ---- Links -------------------------------------------------------------------------------

    /**
     * Every link this node owns: its outgoing links and its pull links, in creation order.
     *
     * @return an unmodifiable view
     */
    public List<ManaLink> links() {
        return Collections.unmodifiableList(links);
    }

    /**
     * The owned links that leave this node, in creation order: a prism's output ports (D89).
     *
     * @return a new list
     */
    List<ManaLink> outgoing() {
        return links.stream().filter(link -> link.from().equals(worldPosition)).toList();
    }

    /**
     * The owned links that arrive here from plain handlers (D87).
     *
     * @return a new list
     */
    List<ManaLink> pulls() {
        return links.stream().filter(link -> link.to().equals(worldPosition)).toList();
    }

    /**
     * The sources of every link arriving here, in creation order: a prism's input ports (D89).
     *
     * @return an unmodifiable view
     */
    List<BlockPos> incoming() {
        return Collections.unmodifiableList(incoming);
    }

    /**
     * Finds an owned link.
     *
     * @param from
     *            its source
     * @param to
     *            its target
     * @return the link, or empty
     */
    public Optional<ManaLink> link(BlockPos from, BlockPos to) {
        return links.stream().filter(link -> link.joins(from, to)).findFirst();
    }

    /** Adds an owned link; {@link ManaLinks} has validated it. */
    void addLink(ManaLink link) {
        links.add(link);
        linksChanged();
    }

    /**
     * Removes an owned link, dropping its fitted lenses at this node (D90).
     *
     * @param level
     *            the level
     * @param from
     *            the link's source
     * @param to
     *            the link's target
     * @return true if the link existed
     */
    boolean removeLink(ServerLevel level, BlockPos from, BlockPos to) {
        int outgoingIndex = outgoing().indexOf(link(from, to).orElse(null));
        for (int i = 0; i < links.size(); i++) {
            ManaLink link = links.get(i);
            if (link.joins(from, to)) {
                links.remove(i);
                dropLenses(level, worldPosition, link.tier());
                if (outgoingIndex >= 0) {
                    outgoingRemoved(level, outgoingIndex);
                }
                linksChanged();
                return true;
            }
        }
        return false;
    }

    /** Records a link arriving from {@code source}, after the existing ones. */
    void addIncoming(BlockPos source) {
        incoming.add(source.immutable());
        setChanged();
    }

    /** Forgets a link arriving from {@code source}. */
    void removeIncoming(BlockPos source) {
        if (incoming.remove(source)) {
            setChanged();
        }
    }

    /** Saves and tells clients after an owned link's lens tier changes (D90). */
    void tierChanged() {
        linksChanged();
    }

    /** Saves and tells clients after the owned links change. */
    private void linksChanged() {
        setChanged();
        if (level instanceof ServerLevel server) {
            sync(server, server.getGameTime());
        }
    }

    /**
     * Drops the lenses fitted to a removed link, lowest tier first (D90).
     *
     * @param level
     *            the level
     * @param pos
     *            where to drop them
     * @param tier
     *            how many are fitted
     */
    static void dropLenses(ServerLevel level, BlockPos pos, int tier) {
        for (int fitted = 1; fitted <= tier; fitted++) {
            Block.popResource(level, pos, new ItemStack(MachineContent.lens(fitted)));
        }
    }

    // ---- The pulse ---------------------------------------------------------------------------

    /**
     * The server ticker: runs a pulse every {@link MachineConfig#PULSE_INTERVAL} ticks of game time
     * (D89).
     *
     * @param level
     *            the server level
     */
    void serverTick(ServerLevel level) {
        long now = level.getGameTime();
        if (now % MachineConfig.PULSE_INTERVAL.get() != 0) {
            return;
        }
        validate(level);
        checkSight(level);
        pull(level, now);
        process(level, now);
        send(level, now);
        sync(level, now);
    }

    /**
     * Drops owned links whose other end, where loaded, is no longer a valid endpoint, and prunes
     * incoming sources that no longer link here. Ends in unloaded chunks are left alone until they
     * load: their absence is not evidence that they are gone.
     */
    private void validate(ServerLevel level) {
        for (ManaLink link : List.copyOf(links)) {
            boolean push = link.from().equals(worldPosition);
            BlockPos other = push ? link.to() : link.from();
            if (!level.isLoaded(other)) {
                continue;
            }
            boolean valid;
            if (push) {
                valid = level.getBlockEntity(other) instanceof ManaNodeBlockEntity node
                    ? node.incoming.contains(worldPosition)
                    : ManaLinks.handler(level, other) != null;
            } else {
                valid = !(level.getBlockEntity(other) instanceof ManaNodeBlockEntity)
                    && ManaLinks.handler(level, other) != null;
            }
            if (!valid) {
                LOGGER.debug("Removing {}: its far end at {} is gone", link, other);
                removeLink(level, link.from(), link.to());
            }
        }
        for (BlockPos source : List.copyOf(incoming)) {
            if (!level.isLoaded(source)) {
                continue;
            }
            boolean linked = level.getBlockEntity(source) instanceof ManaNodeBlockEntity node
                ? node.link(source, worldPosition).isPresent()
                : link(source, worldPosition).isPresent();
            if (!linked) {
                removeIncoming(source);
            }
        }
    }

    /** Re-checks every owned link's line of sight; an unknown result keeps the previous state. */
    private void checkSight(ServerLevel level) {
        for (ManaLink link : links) {
            ManaLinks.obstructed(level, link.from(), link.to()).ifPresent(link::setPaused);
        }
    }

    /**
     * Draws packets over pull links from plain handlers (D87, D89): up to the link's rate, each one
     * single-element packet of {@link MachineConfig#FLAT_PACKET_UNITS} units of the link's pure
     * type. A compound filter cannot be drawn from a flat store, so such a pull link draws nothing.
     */
    private void pull(ServerLevel level, long now) {
        int units = MachineConfig.FLAT_PACKET_UNITS.get();
        for (ManaLink link : pulls()) {
            Optional<ManaType> type = link.filter().pureType();
            if (link.paused() || type.isEmpty() || !level.isLoaded(link.from())) {
                continue;
            }
            IManaHandler source = ManaLinks.handler(level, link.from());
            if (source == null) {
                continue;
            }
            for (int moved = 0; moved < link.rate(); moved++) {
                ManaPacket probe = ManaPacket.leaf(type.get(), units);
                if (!accept(probe, link.from(), now, true)
                    || source.extractPacket(type.get(), units, true).isEmpty()) {
                    break;
                }
                ManaPacket packet = source.extractPacket(type.get(), units, false).orElseThrow(
                    () -> new IllegalStateException("Mana source at " + link.from()
                        + " offered a packet when simulated but not for real"));
                if (!accept(packet, link.from(), now, false)) {
                    throw new IllegalStateException(
                        "Node at " + worldPosition + " accepted a packet when simulated only");
                }
                link.moved(packet, now);
                setChanged();
            }
        }
    }

    /**
     * Moves one packet over an outgoing link, if the target takes it whole (D89). A node target
     * receives it with this node as the source; a plain handler through the capability.
     *
     * @param level
     *            the level
     * @param link
     *            an outgoing link of this node
     * @param packet
     *            the packet
     * @param now
     *            the pulse's game time
     * @param simulate
     *            if true, only report whether it would be taken
     * @return true if taken (or takeable)
     */
    protected boolean deliver(ServerLevel level, ManaLink link, ManaPacket packet, long now,
        boolean simulate) {
        if (link.paused() || !level.isLoaded(link.to())) {
            return false;
        }
        boolean taken;
        if (level.getBlockEntity(link.to()) instanceof ManaNodeBlockEntity node) {
            taken = node.accept(packet, worldPosition, now, simulate);
        } else {
            IManaHandler target = ManaLinks.handler(level, link.to());
            taken = target != null && target.receivePacket(packet, simulate);
        }
        if (taken && !simulate) {
            link.moved(packet, now);
            setChanged();
        }
        return taken;
    }

    // ---- Client sync -------------------------------------------------------------------------

    /** The beams as clients should currently see them. */
    private List<BeamState> beams(long now) {
        long idleTicks = (long) MachineConfig.IDLE_PULSES.get()
            * MachineConfig.PULSE_INTERVAL.get();
        List<BeamState> beams = new ArrayList<>(links.size());
        for (ManaLink link : links) {
            beams.add(new BeamState(link.from(), link.to(), link.tier(), link.paused(),
                link.flowing(now, idleTicks)
                    ? link.lastPacket().map(BeamVisual::of)
                    : Optional.empty()));
        }
        return List.copyOf(beams);
    }

    /** Sends a block update to clients only when the visible beams differ from the last sent. */
    private void sync(ServerLevel level, long now) {
        List<BeamState> beams = beams(now);
        if (!beams.equals(synced)) {
            synced = beams;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(),
                Block.UPDATE_CLIENTS);
        }
    }

    /**
     * The client's copy of this node's beams, for the renderer and beam picking.
     *
     * @return the beams last received; empty on the server
     */
    public List<BeamState> clientBeams() {
        return clientBeams;
    }

    /**
     * A box holding this node and every beam it draws, so the renderer is not culled while any part
     * of a beam is on screen.
     *
     * @return the render bounds
     */
    public AABB beamBounds() {
        AABB box = new AABB(worldPosition);
        for (BeamState beam : clientBeams) {
            box = box.minmax(new AABB(beam.from())).minmax(new AABB(beam.to()));
        }
        return box.inflate(1.0);
    }

    /**
     * Sends the current beams, and nothing else, in chunk and update data. A chunk sent before this
     * node's first pulse still carries its beams.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        List<BeamState> beams = level == null ? synced : beams(level.getGameTime());
        tag.put("beams", BEAMS.encodeStart(NbtOps.INSTANCE, beams).getOrThrow());
        return tag;
    }

    /** Sends {@link #getUpdateTag} when the server calls for a block update. */
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ---- Saving ------------------------------------------------------------------------------

    /** Saves the owned links, the incoming order, and the held packets. */
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("links", LINKS.encodeStart(NbtOps.INSTANCE, links).getOrThrow());
        tag.put("incoming", POSITIONS.encodeStart(NbtOps.INSTANCE, incoming).getOrThrow());
        saveHeld(tag);
    }

    /**
     * Loads saved links and packets on the server, or beams from an update on the client. Saved
     * data that fails to decode is reported naming this node and the problem, and dropped, so the
     * world still loads and the next save is clean.
     */
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("beams")) {
            clientBeams = BEAMS.parse(NbtOps.INSTANCE, tag.get("beams"))
                .resultOrPartial(error -> LOGGER.error(
                    "Unreadable mana beams for node at {}: {}", worldPosition, error))
                .orElse(List.of());
        }
        if (tag.contains("links")) {
            links.clear();
            links.addAll(decodeList(LINKS, tag.get("links"), "links"));
            incoming.clear();
            incoming.addAll(decodeList(POSITIONS, tag.get("incoming"), "incoming link order"));
            loadHeld(tag);
        }
    }

    /**
     * Decodes a saved list, reporting a failure and keeping whatever part decoded.
     *
     * @param <T>
     *            element type
     * @param codec
     *            the list codec
     * @param tag
     *            the saved tag, possibly null
     * @param what
     *            what the list holds, for the report
     * @return the decoded entries
     */
    <T> List<T> decodeList(Codec<List<T>> codec, Tag tag, String what) {
        if (tag == null) {
            LOGGER.error("Node at {} has no saved {}; starting with none", worldPosition, what);
            return List.of();
        }
        return codec.parse(NbtOps.INSTANCE, tag)
            .resultOrPartial(error -> LOGGER.error(
                "Unreadable saved {} for node at {}: {}. Dropping what could not be read.",
                what, worldPosition, error))
            .orElse(List.of());
    }

    // ---- Breaking ----------------------------------------------------------------------------

    /**
     * Called when the block is broken or replaced, not when its chunk unloads (D87). Releases held
     * packets as a burst of their flavors' particles, drops this node's links with their lenses,
     * and removes links other loaded nodes had into this one.
     *
     * @param level
     *            the level
     */
    void broken(ServerLevel level) {
        release(level);
        for (ManaLink link : List.copyOf(links)) {
            if (link.from().equals(worldPosition) && level.isLoaded(link.to())
                && level.getBlockEntity(link.to()) instanceof ManaNodeBlockEntity target) {
                target.removeIncoming(worldPosition);
            }
            dropLenses(level, worldPosition, link.tier());
        }
        links.clear();
        for (BlockPos source : List.copyOf(incoming)) {
            if (level.isLoaded(source)
                && level.getBlockEntity(source) instanceof ManaNodeBlockEntity node) {
                node.removeLink(level, source, worldPosition);
            }
        }
        incoming.clear();
        setChanged();
    }

    /**
     * Releases every held packet at this node as particles of its flavors, spending the mana
     * visibly with no world effect (D87, D93).
     *
     * @param level
     *            the level
     */
    void release(ServerLevel level) {
        spill(level, held());
        clearHeld();
        setChanged();
    }

    /**
     * Spends packets as a burst of their flavors' particles at this node (D87, D93), without
     * touching what the node holds.
     *
     * @param level
     *            the level
     * @param packets
     *            the packets to spend
     */
    void spill(ServerLevel level, List<ManaPacket> packets) {
        Vec3 centre = Vec3.atCenterOf(worldPosition);
        for (ManaPacket packet : packets) {
            int[] units = packet.flavorUnits();
            for (int i = 0; i < units.length; i++) {
                if (units[i] == 0) {
                    continue;
                }
                int argb = ManaColors.argb(ManaType.FLAVORS.get(i));
                Vector3f colour = new Vector3f(((argb >> 16) & 255) / 255f,
                    ((argb >> 8) & 255) / 255f, (argb & 255) / 255f);
                level.sendParticles(new DustParticleOptions(colour, 1.0f), centre.x, centre.y,
                    centre.z, Math.min(64, units[i] * 6), 0.3, 0.3, 0.3, 0.02);
            }
        }
    }

    // ---- Capability ----------------------------------------------------------------------------

    /**
     * This node's mana capability (D24): it reports the packets it holds as flat totals, and takes
     * whole packets offered without a link where the node allows it (directors do; prisms need a
     * port). It never trades flat amounts, which have no structure to keep.
     *
     * @return the handler
     */
    public IManaHandler handler() {
        return handler;
    }

    /** The capability view of a node; see {@link #handler()}. */
    private final class NodeHandler implements IManaHandler {
        @Override
        public Mana getStored() {
            Mana total = Mana.EMPTY;
            for (ManaPacket packet : held()) {
                total = total.plus(packet.toFlat());
            }
            return total;
        }

        @Override
        public int getCapacity() {
            return getStored().total();
        }

        @Override
        public Mana receive(
            Mana offered, boolean simulate) {
            return Mana.EMPTY;
        }

        @Override
        public Mana extract(
            Mana wanted, boolean simulate) {
            return Mana.EMPTY;
        }

        @Override
        public boolean receivePacket(ManaPacket packet, boolean simulate) {
            return level != null && accept(packet, null, level.getGameTime(), simulate);
        }

        @Override
        public Optional<ManaPacket> extractPacket(ManaType element, int units, boolean simulate) {
            return Optional.empty();
        }
    }
}
