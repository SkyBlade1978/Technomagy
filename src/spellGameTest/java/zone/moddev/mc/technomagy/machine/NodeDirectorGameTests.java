package zone.moddev.mc.technomagy.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual block interaction, placement, transform, and saved-state checks for D21 and D23. */
@GameTestHolder("technomagy")
@PrefixGameTestTemplate(false)
public final class NodeDirectorGameTests {
    private NodeDirectorGameTests() {
    }

    /** Both mountings support all bearings, a full click cycle, and legacy saved block states. */
    @GameTest(template = "empty")
    public static void bearingsAndSavedStates(GameTestHelper helper) {
        var block = MachineContent.NODE_DIRECTOR.get();
        var level = helper.getLevel();
        var relative = new BlockPos(1, 1, 1);
        var absolute = helper.absolutePos(relative);
        var player = helper.makeMockPlayer(GameType.CREATIVE);
        player.getAbilities().mayBuild = true;
        helper.assertTrue(block.getStateDefinition().getPossibleStates().size() == 32,
            "Expected eight bearings, two mountings and two power states");
        for (boolean hanging : new boolean[]{false, true}) {
            for (boolean powered : new boolean[]{false, true}) {
                var state = block.defaultBlockState().setValue(NodeDirectorBlock.HANGING, hanging)
                    .setValue(NodeDirectorBlock.POWERED, powered);
                helper.setBlock(relative, state);
                for (var bearing : NodeDirectorBlock.Bearing.values()) {
                    var before = level.getBlockState(absolute);
                    helper.assertTrue(before.getValue(NodeDirectorBlock.DIRECTION) == bearing,
                        "Unexpected bearing before empty-hand use");
                    var hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute,
                        false);
                    helper.assertTrue(before.useWithoutItem(level, player, hit).consumesAction(),
                        "Director did not handle empty-hand use");
                    var after = level.getBlockState(absolute);
                    helper.assertTrue(
                        after.getValue(NodeDirectorBlock.DIRECTION) == bearing.clockwise()
                            && after.getValue(NodeDirectorBlock.HANGING) == hanging
                            && after.getValue(NodeDirectorBlock.POWERED) == powered,
                        "Click must change only the compass bearing");
                    var saved = NbtUtils.writeBlockState(after);
                    var loaded = NbtUtils.readBlockState(
                        level.registryAccess().registryOrThrow(Registries.BLOCK).asLookup(), saved);
                    helper.assertTrue(loaded == after, "Saved direction failed to round trip");
                }
            }
            var old = new CompoundTag();
            old.putString("Name", "technomagy:node_director");
            var properties = new CompoundTag();
            properties.putString("hanging", Boolean.toString(hanging));
            old.put("Properties", properties);
            var loaded = NbtUtils.readBlockState(
                level.registryAccess().registryOrThrow(Registries.BLOCK).asLookup(), old);
            helper.assertTrue(
                loaded.getValue(NodeDirectorBlock.DIRECTION) == NodeDirectorBlock.Bearing.NORTH
                    && loaded.getValue(NodeDirectorBlock.HANGING) == hanging
                    && !loaded.getValue(NodeDirectorBlock.POWERED),
                "Old states must retain mounting and acquire the default bearing");
            // The earlier direction preview saved a bearing but had no power flag.
            properties.putString("direction", "south_west");
            loaded = NbtUtils.readBlockState(
                level.registryAccess().registryOrThrow(Registries.BLOCK).asLookup(), old);
            helper.assertTrue(
                loaded.getValue(NodeDirectorBlock.DIRECTION) == NodeDirectorBlock.Bearing.SOUTH_WEST
                    && loaded.getValue(NodeDirectorBlock.HANGING) == hanging
                    && !loaded.getValue(NodeDirectorBlock.POWERED),
                "Existing preview states must retain their bearing and become unpowered");
        }
        helper.succeed();
    }

    /** Placement sectors, negative yaw, rotation, mirrors, and adventure restrictions agree. */
    @GameTest(template = "empty")
    public static void placementAndTransforms(GameTestHelper helper) {
        var block = MachineContent.NODE_DIRECTOR.get();
        var player = helper.makeMockPlayer(GameType.CREATIVE);
        player.getAbilities().mayBuild = true;
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        var bearings = NodeDirectorBlock.Bearing.values();
        for (var bearing : bearings) {
            for (int turn : new int[]{-360, 0, 360}) {
                player.setYRot(bearing.ordinal() * 45f - 180f + turn);
                for (var face : new Direction[]{Direction.UP, Direction.DOWN}) {
                    var hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
                    var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND,
                        new ItemStack(block), hit);
                    var placed = block.getStateForPlacement(context);
                    helper.assertTrue(placed != null
                        && placed.getValue(NodeDirectorBlock.DIRECTION) == bearing
                        && placed.getValue(NodeDirectorBlock.HANGING) == (face == Direction.DOWN)
                        && !placed.getValue(NodeDirectorBlock.POWERED),
                        "Placement must use the world-space yaw and clicked face");
                }
            }
            var state = block.defaultBlockState().setValue(NodeDirectorBlock.DIRECTION, bearing)
                .setValue(NodeDirectorBlock.POWERED, true);
            for (var rotation : Rotation.values()) {
                var expected = bearings[rotation.rotate(bearing.ordinal(), 8)];
                helper.assertTrue(
                    state.rotate(rotation).getValue(NodeDirectorBlock.DIRECTION) == expected
                        && state.rotate(rotation).getValue(NodeDirectorBlock.POWERED),
                    "Rotation must handle diagonal bearings");
            }
            helper.assertTrue(state.mirror(Mirror.LEFT_RIGHT).getValue(
                NodeDirectorBlock.DIRECTION) == bearings[Math.floorMod(4 - bearing.ordinal(), 8)],
                "North/south mirror failed");
            helper.assertTrue(
                state.mirror(Mirror.FRONT_BACK).getValue(
                    NodeDirectorBlock.DIRECTION) == bearings[Math.floorMod(-bearing.ordinal(), 8)],
                "East/west mirror failed");
        }
        player.getAbilities().mayBuild = false;
        var state = block.defaultBlockState();
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        helper.assertTrue(!state.useWithoutItem(helper.getLevel(), player, hit).consumesAction(),
            "Adventure interaction must not turn the director");
        helper.succeed();
    }

    /** Unpowered placement is dark, and the real light engine propagates and removes level six. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void poweredLightUpdates(GameTestHelper helper) {
        var level = helper.getLevel();
        var relative = new BlockPos(1, 1, 1);
        var absolute = helper.absolutePos(relative);
        var off = MachineContent.NODE_DIRECTOR.get().defaultBlockState();
        // GameTest status beacons and nearby tests can light an otherwise empty
        // template. A closed stone shell isolates this emitter from that light.
        for (var wall : BlockPos.betweenClosed(0, 0, 0, 2, 2, 2)) {
            helper.setBlock(wall, Blocks.STONE);
        }
        helper.setBlock(relative, off);
        helper.assertTrue(!off.getValue(NodeDirectorBlock.POWERED)
            && off.getLightEmission(level, absolute) == 0, "A standalone director must be unlit");
        var on = off.setValue(NodeDirectorBlock.POWERED, true);
        level.setBlockAndUpdate(absolute, on);
        helper.assertTrue(on.getLightEmission(level, absolute) == 6,
            "Powered directors must emit light level six");
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(
                level.getBrightness(LightLayer.BLOCK, absolute) == 6,
                "Powered light has not propagated; observed "
                    + level.getBrightness(LightLayer.BLOCK, absolute)))
            .thenExecute(() -> level.setBlockAndUpdate(absolute, off))
            .thenWaitUntil(() -> helper.assertTrue(
                level.getBrightness(LightLayer.BLOCK, absolute) == 0,
                "Unpowered light has not cleared; observed "
                    + level.getBrightness(LightLayer.BLOCK, absolute)))
            .thenSucceed();
    }
}
