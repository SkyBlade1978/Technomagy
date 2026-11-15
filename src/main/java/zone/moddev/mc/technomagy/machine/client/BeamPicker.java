package zone.moddev.mc.technomagy.machine.client;

import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import zone.moddev.mc.technomagy.machine.BeamState;
import zone.moddev.mc.technomagy.machine.FitLensPayload.BeamRef;
import zone.moddev.mc.technomagy.machine.MachineConfig;
import zone.moddev.mc.technomagy.machine.ManaNodeBlockEntity;

/**
 * Finds the beam under the crosshair (D88, D90), for imprinting a filter and fitting lenses. The
 * pick only names a beam; the server re-validates it.
 *
 * <p>
 * A beam is picked when the view ray passes within {@value #PICK_RADIUS} blocks of its straight
 * line between anchors, nearer than any block the crosshair is on and within the player's reach.
 * The straight line ignores the wander, which is at most half a block and pinned at both ends; the
 * tolerance covers most of it.
 *
 * <p>
 * Client thread only. Searches the block entities of loaded chunks near the player rather than
 * keeping a registry of nodes, so there is no shared state to keep in step.
 */
public final class BeamPicker {
    /** How close the view ray must pass to a beam's line, in blocks. */
    private static final double PICK_RADIUS = 0.3;

    private BeamPicker() {
    }

    /**
     * Picks the nearest beam along the player's view.
     *
     * @param minecraft
     *            the client
     * @return the picked beam, or empty
     */
    public static Optional<BeamRef> pick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return Optional.empty();
        }
        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange();
        HitResult hit = minecraft.hitResult;
        if (hit != null && hit.getType() != HitResult.Type.MISS) {
            reach = Math.min(reach, hit.getLocation().distanceTo(eye));
        }
        Vec3 rayEnd = eye.add(player.getViewVector(1.0f).scale(reach));
        // A beam owned by a node up to one link length away can pass the player. The server's
        // configured length may be anything up to the limit, so search that far.
        int chunks = SectionPos.blockToSectionCoord(
            (int) Math.ceil(reach + MachineConfig.LINK_LENGTH_LIMIT)) + 1;
        int centreX = SectionPos.blockToSectionCoord(player.getBlockX());
        int centreZ = SectionPos.blockToSectionCoord(player.getBlockZ());
        BeamRef best = null;
        double bestAlong = Double.MAX_VALUE;
        for (int x = centreX - chunks; x <= centreX + chunks; x++) {
            for (int z = centreZ - chunks; z <= centreZ + chunks; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    if (!(entity instanceof ManaNodeBlockEntity node)) {
                        continue;
                    }
                    for (BeamState beam : node.clientBeams()) {
                        Vec3 from = BeamAnchors.anchor(level, beam.from(),
                            Vec3.atCenterOf(beam.to()));
                        Vec3 to = BeamAnchors.anchor(level, beam.to(),
                            Vec3.atCenterOf(beam.from()));
                        double[] closest = closest(eye, rayEnd, from, to);
                        if (closest[0] <= PICK_RADIUS && closest[1] < bestAlong) {
                            bestAlong = closest[1];
                            best = new BeamRef(node.getBlockPos(), beam.from(), beam.to());
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The closest approach of two segments: the view ray {@code p0-p1} and a beam {@code q0-q1}.
     * Standard clamped segment-segment distance: solve for the parameters of the closest points on
     * the two infinite lines, then clamp each to its segment, recomputing the other after each
     * clamp.
     *
     * @return {@code [distance, distance along the ray from p0]}
     */
    private static double[] closest(Vec3 p0, Vec3 p1, Vec3 q0, Vec3 q1) {
        Vec3 d1 = p1.subtract(p0);
        Vec3 d2 = q1.subtract(q0);
        Vec3 r = p0.subtract(q0);
        double a = d1.dot(d1);
        double e = d2.dot(d2);
        double f = d2.dot(r);
        double s;
        double t;
        if (a < 1.0e-12) {
            s = 0.0;
            t = clamp01(f / e);
        } else {
            double c = d1.dot(r);
            double b = d1.dot(d2);
            double denominator = a * e - b * b;
            s = denominator > 1.0e-12 ? clamp01((b * f - c * e) / denominator) : 0.0;
            t = (b * s + f) / e;
            if (t < 0.0) {
                t = 0.0;
                s = clamp01(-c / a);
            } else if (t > 1.0) {
                t = 1.0;
                s = clamp01((b - c) / a);
            }
        }
        Vec3 onRay = p0.add(d1.scale(s));
        Vec3 onBeam = q0.add(d2.scale(t));
        return new double[]{onRay.distanceTo(onBeam), Math.sqrt(a) * s};
    }

    /** Clamps to [0, 1]. */
    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
