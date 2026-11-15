package zone.moddev.mc.technomagy.machine;

import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.Nullable;

/**
 * Splits a mana stream into its parts, or combines streams into one, depending on its {@link Mode}
 * (D20, D26). The glass bar lies along its {@link #FACING}; in combine mode a copper collar clamps
 * the middle of the bar.
 *
 * <p>
 * Its {@link PrismBlockEntity} holds its links, ports and slots and does the splitting and
 * combining on each transfer pulse (D89).
 *
 * <p>
 * Using it with an empty hand switches modes, releasing anything the prism held (see
 * {@link PrismBlockEntity#modeChanged}). The focus's linking mode makes links (D87); mode switching
 * stays on the empty hand.
 */
public class PrismBlock extends Block implements EntityBlock {
    /** The direction the glass bar points: away from the player who placed it. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** Whether the prism splits or combines. */
    public static final EnumProperty<Mode> MODE = EnumProperty.create("mode", Mode.class);

    // The model is symmetric end to end, so north/south and east/west share a shape.
    private static final VoxelShape BASE = Shapes.or(
        Block.box(0, 0, 0, 3, 3, 3),
        Block.box(13, 0, 0, 16, 3, 3),
        Block.box(0, 0, 13, 3, 3, 16),
        Block.box(13, 0, 13, 16, 3, 16),
        Block.box(0, 3, 0, 16, 4, 16));
    private static final VoxelShape ALONG_Z = Shapes.or(
        BASE,
        Block.box(6, 4, 3, 10, 7, 5),
        Block.box(6, 4, 11, 10, 7, 13),
        Block.box(4, 7, 2, 12, 15, 14));
    private static final VoxelShape ALONG_X = Shapes.or(
        BASE,
        Block.box(3, 4, 6, 5, 7, 10),
        Block.box(11, 4, 6, 13, 7, 10),
        Block.box(2, 7, 4, 14, 15, 12));

    /**
     * Creates the prism block. Its default state is split mode, facing north.
     *
     * @param properties
     *            block properties; occlusion is turned off here
     */
    public PrismBlock(Properties properties) {
        super(properties.noOcclusion());
        registerDefaultState(
            defaultBlockState().setValue(FACING, Direction.NORTH).setValue(MODE, Mode.SPLIT));
    }

    /** The outline for the bar's axis. */
    @Override
    protected VoxelShape getShape(
        BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? ALONG_Z : ALONG_X;
    }

    @Nullable
    /** Faces away from the placing player, in split mode. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /**
     * Switches between split and combine, with a click sound and an action bar message naming the
     * new mode, then releases anything the prism held under the old mode.
     */
    @Override
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide) {
            Mode mode = state.getValue(MODE).other();
            level.setBlockAndUpdate(pos, state.setValue(MODE, mode));
            if (level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof PrismBlockEntity prism) {
                prism.modeChanged(server);
            }
            level.playSound(
                null, pos, SoundEvents.COPPER_BULB_TURN_ON, SoundSource.BLOCKS, 1.0f, 1.0f);
            player.displayClientMessage(
                Component.translatable(
                    "block.technomagy.prism.mode." + mode.getSerializedName()),
                true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Turns the facing with the structure being rotated. */
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    /** Mirrors the facing with the structure being mirrored. */
    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    /**
     * Creates this block's {@link PrismBlockEntity}.
     *
     * @param pos
     *            the position
     * @param state
     *            the block state
     * @return the new block entity
     */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PrismBlockEntity(pos, state);
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
        if (level.isClientSide || type != MachineContent.PRISM_ENTITY.get()) {
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

    /** Declares {@link #FACING} and {@link #MODE}. */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, MODE);
    }

    /** What a prism does with the mana passing through it (D20, D26). */
    public enum Mode implements StringRepresentable {
        /** Separates a stream into its parts. */
        SPLIT,
        /** Merges incoming streams into one, keeping their proportions. */
        COMBINE;

        /**
         * The mode a switch leads to.
         *
         * @return the other mode
         */
        public Mode other() {
            return this == SPLIT ? COMBINE : SPLIT;
        }

        /**
         * The mode's name in block states and messages.
         *
         * @return {@code split} or {@code combine}
         */
        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
