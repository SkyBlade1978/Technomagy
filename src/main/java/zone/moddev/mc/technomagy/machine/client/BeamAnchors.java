package zone.moddev.mc.technomagy.machine.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import zone.moddev.mc.technomagy.machine.NodeDirectorBlock;
import zone.moddev.mc.technomagy.machine.PrismBlock;

/**
 * Where a beam meets each block (D91): a director's glass tip, the nearer end of a prism's glass
 * bar, or the centre of any other block. The renderer and beam picking both use these, so a player
 * picks the beam where they see it.
 *
 * <p>
 * Stateless and thread-safe; reads only the block state it is given.
 */
public final class BeamAnchors {
    /** A director's tip, in block units above its base (model pixel 15 of 16). */
    private static final double DIRECTOR_TIP = 15.0 / 16.0;

    /** The height of a prism's glass bar's centre line (model pixels 7 to 15). */
    private static final double PRISM_BAR_HEIGHT = 11.0 / 16.0;

    /** How far a prism's bar ends are from the block centre along its axis (pixels 2 and 14). */
    private static final double PRISM_BAR_HALF_LENGTH = 6.0 / 16.0;

    private BeamAnchors() {
    }

    /**
     * The point on a block where a beam toward {@code toward} starts or ends.
     *
     * @param level
     *            the level to read the block from
     * @param pos
     *            the block
     * @param toward
     *            the beam's other end, which picks the nearer bar end of a prism
     * @return the anchor, in world coordinates
     */
    public static Vec3 anchor(BlockGetter level, BlockPos pos, Vec3 toward) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof NodeDirectorBlock) {
            double height = state.getValue(NodeDirectorBlock.HANGING)
                ? 1.0 - DIRECTOR_TIP
                : DIRECTOR_TIP;
            return new Vec3(pos.getX() + 0.5, pos.getY() + height, pos.getZ() + 0.5);
        }
        if (state.getBlock() instanceof PrismBlock) {
            Direction.Axis axis = state.getValue(PrismBlock.FACING).getAxis();
            Vec3 centre = new Vec3(pos.getX() + 0.5, pos.getY() + PRISM_BAR_HEIGHT,
                pos.getZ() + 0.5);
            double along = axis == Direction.Axis.X
                ? toward.x - centre.x
                : toward.z - centre.z;
            double offset = along >= 0 ? PRISM_BAR_HALF_LENGTH : -PRISM_BAR_HALF_LENGTH;
            return axis == Direction.Axis.X
                ? centre.add(offset, 0, 0)
                : centre.add(0, 0, offset);
        }
        return Vec3.atCenterOf(pos);
    }
}
