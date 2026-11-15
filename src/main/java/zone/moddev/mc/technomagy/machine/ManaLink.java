package zone.moddev.mc.technomagy.machine;

import java.util.Objects;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;

import zone.moddev.mc.technomagy.mana.ManaPacket;

/**
 * One directed mana link, {@code from -> to} (D87), as its owning node keeps it.
 *
 * <p>
 * The <b>saved</b> part is the endpoints, the filter (D88) and the rate tier (D90). The
 * <b>runtime</b> part (paused, last transfer, the packet last moved) is rebuilt as pulses run and
 * is never saved: a reloaded link starts unpaused and idle, and its first pulse re-checks the line
 * of sight.
 *
 * <p>
 * The owner is the link's source node, or its target node when the source is a plain mana handler
 * with no block entity of its own (a <b>pull</b> link, D87).
 *
 * <p>
 * Not thread-safe: only the owning block entity touches it, on the server thread.
 */
public final class ManaLink {
    /** The highest rate tier: three lenses fitted, 8 packets per pulse (D90). */
    public static final int MAX_TIER = 3;

    /** Saves the endpoints, filter and tier. Decoding rejects a tier outside 0 to 3. */
    public static final Codec<ManaLink> CODEC = RecordCodecBuilder.create(
        instance -> instance.group(
            BlockPos.CODEC.fieldOf("from").forGetter(ManaLink::from),
            BlockPos.CODEC.fieldOf("to").forGetter(ManaLink::to),
            LinkFilter.CODEC.fieldOf("filter").forGetter(ManaLink::filter),
            Codec.intRange(0, MAX_TIER).fieldOf("tier").forGetter(ManaLink::tier))
            .apply(instance, ManaLink::new));

    private final BlockPos from;
    private final BlockPos to;
    private final LinkFilter filter;
    private int tier;

    private boolean paused;
    private long lastMoved = Long.MIN_VALUE;
    private ManaPacket lastPacket;

    /**
     * Creates a link.
     *
     * @param from
     *            where packets leave
     * @param to
     *            where packets arrive
     * @param filter
     *            what the link carries
     * @param tier
     *            rate tier, 0 (no lens) to {@value #MAX_TIER}
     * @throws IllegalArgumentException
     *             if the endpoints are the same or the tier is out of range
     */
    public ManaLink(BlockPos from, BlockPos to, LinkFilter filter, int tier) {
        this.from = Objects.requireNonNull(from, "from").immutable();
        this.to = Objects.requireNonNull(to, "to").immutable();
        this.filter = Objects.requireNonNull(filter, "filter");
        if (from.equals(to)) {
            throw new IllegalArgumentException("A link needs two different endpoints: " + from);
        }
        setTier(tier);
    }

    /**
     * Where packets leave.
     *
     * @return the source position
     */
    public BlockPos from() {
        return from;
    }

    /**
     * Where packets arrive.
     *
     * @return the target position
     */
    public BlockPos to() {
        return to;
    }

    /**
     * What the link carries (D88).
     *
     * @return the filter
     */
    public LinkFilter filter() {
        return filter;
    }

    /**
     * How many lenses are fitted (D90).
     *
     * @return 0 to {@value #MAX_TIER}
     */
    public int tier() {
        return tier;
    }

    /**
     * Sets the rate tier.
     *
     * @param tier
     *            0 to {@value #MAX_TIER}
     * @throws IllegalArgumentException
     *             if out of range
     */
    void setTier(int tier) {
        if (tier < 0 || tier > MAX_TIER) {
            throw new IllegalArgumentException("Link tier must be 0 to " + MAX_TIER + ": " + tier);
        }
        this.tier = tier;
    }

    /**
     * Whole packets this link moves per pulse (D89, D90): 1, 2, 4 or 8.
     *
     * @return the rate
     */
    public int rate() {
        return 1 << tier;
    }

    /**
     * Whether the line between the endpoints is obstructed, so the link moves nothing (D87).
     *
     * @return true while paused
     */
    public boolean paused() {
        return paused;
    }

    /** Records the latest line-of-sight check. */
    void setPaused(boolean paused) {
        this.paused = paused;
    }

    /**
     * Records a transfer.
     *
     * @param packet
     *            the packet moved
     * @param gameTime
     *            the pulse's game time
     */
    void moved(ManaPacket packet, long gameTime) {
        lastPacket = packet;
        lastMoved = gameTime;
    }

    /**
     * Whether the link has moved a packet recently enough to draw as flowing (D91).
     *
     * @param gameTime
     *            the current game time
     * @param idleTicks
     *            ticks without a transfer after which the link is idle
     * @return true when flowing
     */
    boolean flowing(long gameTime, long idleTicks) {
        return lastPacket != null && gameTime - lastMoved <= idleTicks;
    }

    /**
     * The packet this link moved most recently, which imprinting copies (D88).
     *
     * @return the packet, or empty if it has moved none since loading
     */
    public Optional<ManaPacket> lastPacket() {
        return Optional.ofNullable(lastPacket);
    }

    /**
     * Whether this link joins the given endpoints, in that direction.
     *
     * @param source
     *            the source to compare
     * @param target
     *            the target to compare
     * @return true for the same directed pair
     */
    public boolean joins(BlockPos source, BlockPos target) {
        return from.equals(source) && to.equals(target);
    }

    /** The endpoints, filter and tier, for logs. */
    @Override
    public String toString() {
        return "ManaLink[" + from.toShortString() + " -> " + to.toShortString() + ", "
            + filter.shape() + ", tier " + tier + "]";
    }
}
