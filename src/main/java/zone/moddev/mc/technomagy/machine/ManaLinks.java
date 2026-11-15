package zone.moddev.mc.technomagy.machine;

import java.util.Locale;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import zone.moddev.mc.technomagy.mana.IManaHandler;
import zone.moddev.mc.technomagy.mana.ManaCapabilities;

/**
 * Making and removing mana links (D87), and the line-of-sight rule links live by. The focus's
 * linking mode calls {@link #toggle}; node pulses call {@link #obstructed}.
 *
 * <p>
 * Stateless; every method must run on the server thread that owns the level.
 */
public final class ManaLinks {
    private ManaLinks() {
    }

    /** The outcome of a link request, each with its own player message. */
    public enum Result {
        /** The link was made. */
        CREATED,
        /** The link already existed and was removed. */
        REMOVED,
        /** One end is neither a node nor a mana handler. */
        NOT_ENDPOINT,
        /** Both ends are the same block. */
        SAME_BLOCK,
        /** One end is in an unloaded chunk. */
        UNLOADED,
        /** Neither end is a director or prism, so nothing could own the link. */
        NEEDS_NODE,
        /** The ends are farther apart than {@link MachineConfig#MAX_LINK_LENGTH}. */
        TOO_FAR,
        /** A block is in the way. */
        NO_LINE_OF_SIGHT,
        /** A prism end has no free port for this link (D89). */
        PORTS_FULL,
        /** The focus has no filter to give a new link (D88). */
        NEEDS_FILTER;

        /**
         * The action bar message for this outcome.
         *
         * @return a translatable component, {@code message.technomagy.link.<outcome>}
         */
        public Component message() {
            return Component.translatable(
                "message.technomagy.link." + name().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * A position's mana capability, unsided.
     *
     * @param level
     *            the level
     * @param pos
     *            the position, which must be loaded
     * @return the handler, or null if the block has none
     */
    static IManaHandler handler(Level level, BlockPos pos) {
        return level.getCapability(ManaCapabilities.BLOCK, pos, null);
    }

    /**
     * Whether a block can be a link end: a node, or any block with the mana capability (D87).
     *
     * @param level
     *            the level
     * @param pos
     *            the position, which must be loaded
     * @return true for a valid endpoint
     */
    public static boolean isEndpoint(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ManaNodeBlockEntity
            || handler(level, pos) != null;
    }

    /**
     * Removes the link {@code from -> to} if it exists, or otherwise makes it with the given filter
     * (D87). A new link needs both ends loaded and valid, at least one of them a node, the two
     * within {@link MachineConfig#MAX_LINK_LENGTH} of each other with a clear line between them,
     * and a free port at any prism end (D89). It is owned by its source node, or by its target when
     * the source is a plain handler.
     *
     * @param level
     *            the level
     * @param from
     *            the source
     * @param to
     *            the target
     * @param filter
     *            the filter for a new link; empty if the focus has none, which still allows
     *            removing an existing link
     * @return what happened
     */
    public static Result toggle(ServerLevel level, BlockPos from, BlockPos to,
        Optional<LinkFilter> filter) {
        if (from.equals(to)) {
            return Result.SAME_BLOCK;
        }
        if (!level.isLoaded(from) || !level.isLoaded(to)) {
            return Result.UNLOADED;
        }
        ManaNodeBlockEntity source = node(level, from);
        ManaNodeBlockEntity target = node(level, to);
        if (!isEndpoint(level, from) || !isEndpoint(level, to)) {
            return Result.NOT_ENDPOINT;
        }
        ManaNodeBlockEntity owner = source != null ? source : target;
        if (owner == null) {
            return Result.NEEDS_NODE;
        }
        if (owner.link(from, to).isPresent()) {
            owner.removeLink(level, from, to);
            if (target != null) {
                target.removeIncoming(from);
            }
            return Result.REMOVED;
        }
        if (filter.isEmpty()) {
            return Result.NEEDS_FILTER;
        }
        if (Vec3.atCenterOf(from).distanceTo(Vec3.atCenterOf(to)) > MachineConfig.MAX_LINK_LENGTH
            .get()) {
            return Result.TOO_FAR;
        }
        if (obstructed(level, from, to).orElse(true)) {
            return Result.NO_LINE_OF_SIGHT;
        }
        if (source != null && source.outgoing().size() >= source.maxOutgoing()
            || target != null && target.incoming().size() >= target.maxIncoming()) {
            return Result.PORTS_FULL;
        }
        owner.addLink(new ManaLink(from, to, filter.get(), 0));
        if (target != null) {
            target.addIncoming(from);
        }
        return Result.CREATED;
    }

    /**
     * Finds a link by its owner and ends, as beam picking names it.
     *
     * @param level
     *            the level
     * @param owner
     *            the node that owns the link
     * @param from
     *            its source
     * @param to
     *            its target
     * @return the link, or empty if the owner is not a loaded node or has no such link
     */
    public static Optional<ManaLink> find(Level level, BlockPos owner, BlockPos from,
        BlockPos to) {
        if (!level.isLoaded(owner)) {
            return Optional.empty();
        }
        ManaNodeBlockEntity node = node(level, owner);
        return node == null ? Optional.empty() : node.link(from, to);
    }

    /**
     * Whether a solid block lies on the straight line between two endpoints' centres (D87). The
     * endpoints themselves never block. A block blocks when its collision shape crosses the line,
     * so a slab under the line or an open fence gate does not.
     *
     * @param level
     *            the level
     * @param from
     *            one end
     * @param to
     *            the other end
     * @return whether the line is obstructed, or empty if any block on it is in an unloaded chunk,
     *         since looking would load that chunk
     */
    public static Optional<Boolean> obstructed(Level level, BlockPos from, BlockPos to) {
        Vec3 start = Vec3.atCenterOf(from);
        Vec3 end = Vec3.atCenterOf(to);
        Sight sight = BlockGetter.traverseBlocks(start, end, null, (context, pos) -> {
            if (pos.equals(from) || pos.equals(to)) {
                return null;
            }
            if (!level.isLoaded(pos)) {
                return Sight.UNKNOWN;
            }
            BlockState state = level.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(level, pos);
            return !shape.isEmpty() && shape.clip(start, end, pos) != null ? Sight.BLOCKED : null;
        }, context -> Sight.CLEAR);
        return switch (sight) {
            case CLEAR -> Optional.of(false);
            case BLOCKED -> Optional.of(true);
            case UNKNOWN -> Optional.empty();
        };
    }

    /** The loaded node at a position, or null. */
    private static ManaNodeBlockEntity node(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ManaNodeBlockEntity node ? node : null;
    }

    /** A line-of-sight walk's outcome. */
    private enum Sight {
        CLEAR, BLOCKED, UNKNOWN
    }
}
