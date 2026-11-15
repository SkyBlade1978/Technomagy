package zone.moddev.mc.technomagy.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Reconciles saved light with the director's current power state when its chunk loads (D23).
 * Earlier preview builds emitted a constant seven; Minecraft can retain that cached light even
 * after reading the same block as an unpowered state. Checking the emitter removes the stale light
 * without changing its bearing, mounting, or power.
 *
 * <p>
 * Chunk-load callbacks can run before the chunk is fully published. This handler reads only the
 * supplied chunk and queues work through the server's threaded light engine; it never requests
 * another chunk or reads the ledger. A section palette rejects sections without directors before
 * any per-block scan. Nothing runs on ordinary server ticks.
 */
@EventBusSubscriber(modid = "technomagy")
public final class NodeDirectorLightUpdates {
    private NodeDirectorLightUpdates() {
    }

    /** Queues a light recheck for each director in an already-loading server chunk. */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)
            || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        var director = MachineContent.NODE_DIRECTOR.get();
        var sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        var light = level.getChunkSource().getLightEngine();
        for (int index = 0; index < sections.length; index++) {
            var section = sections[index];
            if (!section.maybeHas(state -> state.is(director))) {
                continue;
            }
            int minY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (section.getBlockState(x, y, z).is(director)) {
                            light.checkBlock(new BlockPos(minX + x, minY + y, minZ + z));
                        }
                    }
                }
            }
        }
    }
}
