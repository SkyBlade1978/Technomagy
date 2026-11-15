package zone.moddev.mc.technomagy.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.Nullable;

/**
 * A machine piece that directs a mana stream (D21) through the glass director at its tip. It stands
 * on a floor, or hangs upside down when placed against a ceiling.
 *
 * <p>
 * Its {@link NodeDirectorBlockEntity} holds its links and packet queue (D89). Links are made with
 * the focus's linking mode (D87) and drawn as braided beams (D91).
 *
 * <p>
 * It does not need support: removing the floor or ceiling it was placed against leaves it in place.
 * Its light is the whole block's ({@value #LIGHT}); the glow of the glass tip itself is set in its
 * block model.
 */
public class NodeDirectorBlock extends Block implements EntityBlock {
    /** Whether it hangs from a ceiling (upside down) rather than standing on a floor. */
    public static final BooleanProperty HANGING = BlockStateProperties.HANGING;

    /** The light level the block gives off. */
    public static final int LIGHT = 7;

    // Outlines in model pixels, matching the model's feet, platform, body and post with tip. The
    // hanging shape is the standing one flipped top to bottom.

    private static final VoxelShape STANDING = Shapes.or(
        Block.box(0, 0, 0, 3, 3, 3),
        Block.box(13, 0, 0, 16, 3, 3),
        Block.box(0, 0, 13, 3, 3, 16),
        Block.box(13, 0, 13, 16, 3, 16),
        Block.box(0, 3, 0, 16, 4, 16),
        Block.box(6, 4, 6, 10, 8, 10),
        Block.box(7, 8, 7, 9, 16, 9));
    private static final VoxelShape HANGING_SHAPE = Shapes.or(
        Block.box(0, 13, 0, 3, 16, 3),
        Block.box(13, 13, 0, 16, 16, 3),
        Block.box(0, 13, 13, 3, 16, 16),
        Block.box(13, 13, 13, 16, 16, 16),
        Block.box(0, 12, 0, 16, 13, 16),
        Block.box(6, 8, 6, 10, 12, 10),
        Block.box(7, 0, 7, 9, 8, 9));

    /**
     * Creates the node director block.
     *
     * @param properties
     *            block properties; occlusion is turned off and the light level set here
     */
    public NodeDirectorBlock(Properties properties) {
        super(properties.noOcclusion().lightLevel(state -> LIGHT));
        registerDefaultState(defaultBlockState().setValue(HANGING, false));
    }

    /** The standing or hanging outline. */
    @Override
    protected VoxelShape getShape(
        BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(HANGING) ? HANGING_SHAPE : STANDING;
    }

    /** Hangs when placed against the underside of a block; stands otherwise. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(HANGING, context.getClickedFace() == Direction.DOWN);
    }

    /**
     * Creates this block's {@link NodeDirectorBlockEntity}.
     *
     * @param pos
     *            the position
     * @param state
     *            the block state
     * @return the new block entity
     */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NodeDirectorBlockEntity(pos, state);
    }

    /**
     * Ticks the block entity on the server only; clients just draw the synced beams (D92).
     *
     * @param level
     *            the level
     * @param state
     *            the block state
     * @param type
     *            the block entity type being ticked
     * @return the server ticker, or null on the client or for another type
     */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
        Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != MachineContent.NODE_DIRECTOR_ENTITY.get()) {
            return null;
        }
        return (tickLevel, pos, tickState, entity) -> ((ManaNodeBlockEntity) entity)
            .serverTick((ServerLevel) tickLevel);
    }

    /**
     * When the block is broken or replaced (not merely changing state), releases what the node
     * holds and removes its links (D87).
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
        boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server
            && level.getBlockEntity(pos) instanceof ManaNodeBlockEntity node) {
            node.broken(server);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** Declares {@link #HANGING}. */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HANGING);
    }
}
