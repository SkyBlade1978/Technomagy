package zone.moddev.mc.technomagy.machine.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import zone.moddev.mc.technomagy.machine.BeamState;
import zone.moddev.mc.technomagy.machine.BeamVisual;
import zone.moddev.mc.technomagy.machine.MachineConfig;
import zone.moddev.mc.technomagy.machine.ManaNodeBlockEntity;
import zone.moddev.mc.technomagy.mana.ManaColors;
import zone.moddev.mc.technomagy.mana.ManaType;

/**
 * Draws the beams a node owns (D91), entirely procedurally: geometry and vertex colour, no
 * textures. Each node draws the links it owns, so every link is drawn exactly once.
 *
 * <p>
 * <b>A flowing beam</b> is a braid built around a centre line:
 *
 * <pre>
 *  source anchor                                           target anchor
 *     *---.__        .-~~-.         .-~~-.        __.---*    centre line: wanders in 3D,
 *            `-.__.-'      `-.__.-'      `-.__.-'            pinned at both anchors
 *        ~~ strands twist around it, one per flavor ~~
 *        ( ( helix fades in and out along it and over time ) )
 *          .        .        .        .     motes ride toward the target
 * </pre>
 *
 * <ul>
 * <li><b>Centre line.</b> The straight line between the anchors ({@link BeamAnchors}) plus a
 * wander: two sums of sines across the line, in the two directions perpendicular to it. Phases come
 * from a hash of the endpoints, so every player sees the same shape, and drift slowly with time.
 * The wander is multiplied by {@code sin(pi t)}, which is 0 at both ends, so the beam always leaves
 * and enters its blocks exactly at their anchors.</li>
 * <li><b>Strands.</b> One per flavor the packet in flight carries, its width growing with the
 * square root of that flavor's share, so a minor flavor stays visible. Strands sit evenly around
 * the centre line and twist about it as they go, converging at the anchors. A Raw packet carries
 * every flavor equally, so it draws six equal strands (D15). A life &amp; death pair (D35) shares
 * one place in the braid, its Light and Dark strands wound tightly round each other.</li>
 * <li><b>Helix.</b> One, sometimes two, pale coils around the braid. Each coil's opacity is a
 * product of a travelling wave along the beam and a slow wave in time, so the coil fades in and out
 * as the beam runs; the second coil appears only while its own slow wave is positive, giving a
 * double helix some of the time.</li>
 * <li><b>Motes.</b> Small bright squares riding the strands toward the target.</li>
 * </ul>
 * Every strand is drawn twice: a wide faint halo, then a narrow bright core. The render type adds
 * light rather than covering what is behind it, so overlaps glow.
 *
 * <p>
 * <b>An idle link</b> (D91) is a faint neutral thread drawn only for a player holding an item in
 * the {@code technomagy:reveals_mana_links} tag (the focus). A paused link (D87) moves nothing, so
 * it is idle, and its thread flickers.
 *
 * <p>
 * The client config can turn the wander, helix and motes off ({@code reducedEffects}), leaving
 * straight strands.
 *
 * <p>
 * Runs on the render thread only.
 */
public final class ManaBeamRenderer implements BlockEntityRenderer<ManaNodeBlockEntity> {
    /** Items that reveal idle links while held: the focus, by default. */
    public static final TagKey<Item> REVEALS_LINKS = ItemTags.create(
        ResourceLocation.fromNamespaceAndPath("technomagy", "reveals_mana_links"));

    /** Samples per block of beam length, bounded below and above. */
    private static final double SAMPLES_PER_BLOCK = 6.0;
    private static final int MIN_SAMPLES = 8;
    private static final int MAX_SAMPLES = 160;

    /** Distance of the strands from the centre line, in blocks. */
    private static final double STRAND_RADIUS = 0.06;

    /** Distance of a life &amp; death pair's two strands from their shared place. */
    private static final double PAIR_RADIUS = 0.022;

    /** Distance of the helix coils from the centre line. */
    private static final double HELIX_RADIUS = 0.13;

    /** Strand twist, in turns per block of length. */
    private static final double STRAND_TWIST = 0.35;

    /** How fast motes travel, in blocks per second. */
    private static final double MOTE_SPEED = 2.0;

    /** Seconds in the game-time window used for animation, keeping float time precise. */
    private static final long TIME_WINDOW_TICKS = 20L * 60 * 60 * 24;

    /** The neutral colour of idle threads and the pale base of helix coils. */
    private static final int NEUTRAL = 0xffd8d8e8;

    /**
     * Creates the renderer.
     *
     * @param context
     *            the renderer context; unused
     */
    public ManaBeamRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** Draws every beam this node owns, as the class describes. */
    @Override
    public void render(ManaNodeBlockEntity node, float partialTick, PoseStack poseStack,
        MultiBufferSource buffers, int packedLight, int packedOverlay) {
        List<BeamState> beams = node.clientBeams();
        Level level = node.getLevel();
        if (beams.isEmpty() || level == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        boolean reveal = player != null && (player.getMainHandItem().is(REVEALS_LINKS)
            || player.getOffhandItem().is(REVEALS_LINKS));
        float time = ((level.getGameTime() % TIME_WINDOW_TICKS) + partialTick) / 20.0f;
        Vec3 origin = Vec3.atLowerCornerOf(node.getBlockPos());
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition().subtract(origin);
        Style style = Style.read();
        VertexConsumer out = buffers.getBuffer(BeamRenderTypes.BEAM);
        PoseStack.Pose pose = poseStack.last();
        for (BeamState beam : beams) {
            Vec3 from = BeamAnchors.anchor(level, beam.from(), Vec3.atCenterOf(beam.to()))
                .subtract(origin);
            Vec3 to = BeamAnchors.anchor(level, beam.to(), Vec3.atCenterOf(beam.from()))
                .subtract(origin);
            if (from.distanceToSqr(to) < 1.0e-4) {
                continue;
            }
            Path path = new Path(from, to, seed(beam.from(), beam.to()), style, time);
            Draw draw = new Draw(out, pose, camera);
            if (beam.flowing().isPresent()) {
                braid(draw, path, beam.flowing().get(), style, time);
            } else if (reveal) {
                thread(draw, path, beam.paused(), time);
            }
        }
    }

    /** Draws a flowing beam's strands, helix and motes. */
    private static void braid(Draw draw, Path path, BeamVisual visual, Style style, float time) {
        List<Slot> slots = slots(visual);
        int samples = path.samples();
        double length = path.length();
        for (int s = 0; s < slots.size(); s++) {
            Slot slot = slots.get(s);
            double slotAngle = Math.PI * 2 * s / slots.size();
            for (int k = 0; k < slot.strands().size(); k++) {
                Strand strand = slot.strands().get(k);
                Vec3[] points = new Vec3[samples];
                float[] width = new float[samples];
                float[] alpha = new float[samples];
                for (int i = 0; i < samples; i++) {
                    double t = path.t(i);
                    double spread = Math.sqrt(Math.sin(Math.PI * t));
                    double angle = slotAngle
                        + Math.PI * 2 * (STRAND_TWIST * t * length + 0.25 * time);
                    double radius = slots.size() == 1 ? 0.0 : STRAND_RADIUS * spread;
                    Vec3 point = path.offset(i, radius, angle);
                    if (slot.strands().size() == 2) {
                        // A life & death pair winds round its own place in the braid, four times
                        // as tightly as the braid itself twists.
                        double pairAngle = Math.PI * k
                            + Math.PI * 2 * 4 * (STRAND_TWIST * t * length + 0.25 * time);
                        point = point.add(path.across(i, PAIR_RADIUS * spread, pairAngle));
                    }
                    points[i] = point;
                    width[i] = (float) strand.width();
                    alpha[i] = 1.0f;
                }
                draw.ribbon(points, scaled(width, 3.0f), strand.argb(), scaled(alpha, 0.16f));
                draw.ribbon(points, width, strand.argb(), scaled(alpha, 0.85f));
            }
        }
        if (style.reduced()) {
            return;
        }
        int pale = lighten(mix(visual), 0.5);
        helix(draw, path, pale, 0.0, style, time, 1.0f);
        // The second coil shows only while its own slow wave is positive: a double helix some of
        // the time.
        float second = (float) Mth.clamp(Math.sin(time * 0.23 + path.phase(5)) * 2.0, 0.0, 1.0);
        if (second > 0.0f) {
            helix(draw, path, pale, Math.PI, style, time, second);
        }
        motes(draw, path, slots, style, time);
    }

    /** Draws one helix coil, fading along the beam and over time. */
    private static void helix(Draw draw, Path path, int argb, double phase, Style style,
        float time, float strength) {
        int samples = path.samples();
        Vec3[] points = new Vec3[samples];
        float[] width = new float[samples];
        float[] alpha = new float[samples];
        double turns = style.helixFrequency() * path.length();
        float slow = (float) (0.5 + 0.5 * Math.sin(time * 0.37 + path.phase(4)));
        for (int i = 0; i < samples; i++) {
            double t = path.t(i);
            double spread = Math.sqrt(Math.sin(Math.PI * t));
            points[i] = path.offset(i, HELIX_RADIUS * spread,
                phase + Math.PI * 2 * (turns * t - 0.6 * time));
            width[i] = 0.012f;
            double wave = 0.5 + 0.5 * Math.sin(Math.PI * 2 * (0.6 * t) - 0.8 * time + phase);
            alpha[i] = (float) (smoothstep(0.35, 0.85, wave) * slow * strength * 0.7);
        }
        draw.ribbon(points, width, argb, alpha);
    }

    /** Draws motes riding the strands toward the target. */
    private static void motes(Draw draw, Path path, List<Slot> slots, Style style, float time) {
        int count = (int) Math.ceil(style.moteDensity() * path.length());
        if (count == 0) {
            return;
        }
        for (int m = 0; m < count; m++) {
            double t = frac((double) m / count + time * MOTE_SPEED / path.length());
            int slotIndex = m % slots.size();
            Slot slot = slots.get(slotIndex);
            Strand strand = slot.strands().get(m % slot.strands().size());
            double spread = Math.sqrt(Math.sin(Math.PI * t));
            double angle = Math.PI * 2 * slotIndex / slots.size()
                + Math.PI * 2 * (STRAND_TWIST * t * path.length() + 0.25 * time);
            double radius = slots.size() == 1 ? 0.0 : STRAND_RADIUS * spread;
            Vec3 at = path.offsetAt(t, radius, angle);
            float fade = (float) Math.min(1.0, Math.sin(Math.PI * t) * 3.0);
            draw.billboard(at, 0.035f, lighten(strand.argb(), 0.3), fade);
        }
    }

    /** Draws an idle link's faint thread; a paused link's thread flickers (D87, D91). */
    private static void thread(Draw draw, Path path, boolean paused, float time) {
        int samples = path.samples();
        Vec3[] points = new Vec3[samples];
        float[] width = new float[samples];
        float[] alpha = new float[samples];
        float flicker = paused && Math.sin(time * 40.0) < 0 ? 0.25f : 1.0f;
        for (int i = 0; i < samples; i++) {
            points[i] = path.centre(i);
            width[i] = 0.012f;
            alpha[i] = 0.35f * flicker;
        }
        draw.ribbon(points, width, NEUTRAL, alpha);
    }

    /**
     * Groups the visual's flavors into braid places: one strand per flavor with units, with a life
     * &amp; death pair's Light and Dark sharing one place.
     */
    private static List<Slot> slots(BeamVisual visual) {
        int[] units = visual.flavorUnits();
        int total = 0;
        for (int amount : units) {
            total += amount;
        }
        List<Slot> slots = new ArrayList<>();
        Strand light = null;
        Strand dark = null;
        for (int i = 0; i < units.length; i++) {
            if (units[i] == 0) {
                continue;
            }
            ManaType flavor = ManaType.FLAVORS.get(i);
            double share = (double) units[i] / total;
            Strand strand = new Strand(ManaColors.argb(flavor), 0.012 + 0.03 * Math.sqrt(share));
            if (visual.lifeAndDeath() && flavor == ManaType.LIGHT) {
                light = strand;
            } else if (visual.lifeAndDeath() && flavor == ManaType.DARK) {
                dark = strand;
            } else {
                slots.add(new Slot(List.of(strand)));
            }
        }
        if (light != null && dark != null) {
            slots.add(new Slot(List.of(light, dark)));
        } else {
            // A pair flag without both flavors cannot happen from the server; draw what exists.
            if (light != null) {
                slots.add(new Slot(List.of(light)));
            }
            if (dark != null) {
                slots.add(new Slot(List.of(dark)));
            }
        }
        return slots;
    }

    /** The amount-weighted average colour of a visual's flavors. */
    private static int mix(BeamVisual visual) {
        int[] units = visual.flavorUnits();
        double red = 0;
        double green = 0;
        double blue = 0;
        double total = 0;
        for (int i = 0; i < units.length; i++) {
            if (units[i] == 0) {
                continue;
            }
            int argb = ManaColors.argb(ManaType.FLAVORS.get(i));
            red += ((argb >> 16) & 255) * units[i];
            green += ((argb >> 8) & 255) * units[i];
            blue += (argb & 255) * units[i];
            total += units[i];
        }
        return 0xff000000 | (int) (red / total) << 16 | (int) (green / total) << 8
            | (int) (blue / total);
    }

    /** Moves a colour toward white by {@code amount} (0 to 1). */
    private static int lighten(int argb, double amount) {
        int red = (int) Mth.lerp(amount, (argb >> 16) & 255, 255);
        int green = (int) Mth.lerp(amount, (argb >> 8) & 255, 255);
        int blue = (int) Mth.lerp(amount, argb & 255, 255);
        return 0xff000000 | red << 16 | green << 8 | blue;
    }

    /** A copy of {@code values}, each multiplied by {@code factor}. */
    private static float[] scaled(float[] values, float factor) {
        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i] * factor;
        }
        return result;
    }

    /** Hermite smoothstep of {@code x} between {@code edge0} and {@code edge1}. */
    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    /** The fractional part of {@code x}, in [0, 1). */
    private static double frac(double x) {
        return x - Math.floor(x);
    }

    /** A stable seed for a link's shape, from its endpoints in order. */
    private static long seed(BlockPos from, BlockPos to) {
        long seed = from.asLong() * 0x9E3779B97F4A7C15L ^ to.asLong() * 0xC2B2AE3D27D4EB4FL;
        seed ^= seed >>> 31;
        return seed * 0xBF58476D1CE4E5B9L;
    }

    /** Draws beams far beyond the owning block, which may itself be off screen. */
    @Override
    public boolean shouldRenderOffScreen(ManaNodeBlockEntity node) {
        return true;
    }

    /** Beams can be long; keep them visible from a fair distance. */
    @Override
    public int getViewDistance() {
        return 96;
    }

    /** A box around every beam this node owns, so culling never clips a visible beam. */
    @Override
    public AABB getRenderBoundingBox(ManaNodeBlockEntity node) {
        return node.beamBounds();
    }

    /** The client config, read once per frame. */
    private record Style(double wander, double helixFrequency, double moteDensity,
        boolean reduced) {
        /** Reads the client config; reduced effects zero the wander. */
        static Style read() {
            boolean reduced = MachineConfig.REDUCED_EFFECTS.get();
            return new Style(reduced ? 0.0 : MachineConfig.WANDER_AMPLITUDE.get(),
                MachineConfig.HELIX_FREQUENCY.get(), MachineConfig.MOTE_DENSITY.get(), reduced);
        }
    }

    /** One coloured strand of the braid, with its half-width in blocks. */
    private record Strand(int argb, double width) {
    }

    /** One place in the braid: one strand, or a life &amp; death pair's two. */
    private record Slot(List<Strand> strands) {
    }

    /**
     * A beam's centre line, sampled once per beam per frame, with a fixed frame of two directions
     * across it for placing strands and coils.
     */
    private static final class Path {
        private final Vec3 from;
        private final Vec3 to;
        private final double length;
        private final Vec3 across1;
        private final Vec3 across2;
        private final double[] phases = new double[6];
        private final double wander;
        private final float time;
        private final Vec3[] centres;

        /** Samples the centre line between two anchors, in the renderer's local coordinates. */
        Path(Vec3 from, Vec3 to, long seed, Style style, float time) {
            this.from = from;
            this.to = to;
            this.length = from.distanceTo(to);
            this.wander = style.wander();
            this.time = time;
            Vec3 direction = to.subtract(from).normalize();
            // Any vector not parallel to the beam gives a frame; use world up unless the beam is
            // nearly vertical, then world X.
            Vec3 reference = Math.abs(direction.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            this.across1 = direction.cross(reference).normalize();
            this.across2 = direction.cross(across1).normalize();
            long state = seed;
            for (int i = 0; i < phases.length; i++) {
                state = state * 6364136223846793005L + 1442695040888963407L;
                phases[i] = ((state >>> 11) / (double) (1L << 53)) * Math.PI * 2;
            }
            int samples = Mth.clamp((int) Math.ceil(length * SAMPLES_PER_BLOCK), MIN_SAMPLES,
                MAX_SAMPLES);
            this.centres = new Vec3[samples];
            for (int i = 0; i < samples; i++) {
                centres[i] = centreAt(t(i));
            }
        }

        /** The number of samples along the beam. */
        int samples() {
            return centres.length;
        }

        /** The beam's straight length, in blocks. */
        double length() {
            return length;
        }

        /** A random phase fixed for this link, 0 to 5. */
        double phase(int index) {
            return phases[index];
        }

        /** The position along the beam of sample {@code i}, from 0 at the source to 1. */
        double t(int i) {
            return (double) i / (centres.length - 1);
        }

        /** The centre line at sample {@code i}. */
        Vec3 centre(int i) {
            return centres[i];
        }

        /** The centre line at any {@code t}: the straight line plus the pinned wander. */
        Vec3 centreAt(double t) {
            double pin = Math.sin(Math.PI * t);
            double sway1 = 0.6 * Math.sin(Math.PI * 2 * 1.3 * t + phases[0] + 0.7 * time)
                + 0.4 * Math.sin(Math.PI * 2 * 2.9 * t + phases[1] - 1.1 * time);
            double sway2 = 0.6 * Math.sin(Math.PI * 2 * 1.7 * t + phases[2] + 0.5 * time)
                + 0.4 * Math.sin(Math.PI * 2 * 3.3 * t + phases[3] + 0.9 * time);
            return from.lerp(to, t)
                .add(across1.scale(wander * pin * sway1))
                .add(across2.scale(wander * pin * sway2));
        }

        /** A point {@code radius} from the centre line at sample {@code i}, at {@code angle}. */
        Vec3 offset(int i, double radius, double angle) {
            return centres[i].add(across(i, radius, angle));
        }

        /** A point {@code radius} from the centre line at any {@code t}, at {@code angle}. */
        Vec3 offsetAt(double t, double radius, double angle) {
            return centreAt(t).add(across1.scale(radius * Math.cos(angle)))
                .add(across2.scale(radius * Math.sin(angle)));
        }

        /** A vector across the beam of length {@code radius}, at {@code angle}. */
        Vec3 across(int i, double radius, double angle) {
            return across1.scale(radius * Math.cos(angle))
                .add(across2.scale(radius * Math.sin(angle)));
        }
    }

    /** Emits camera-facing quads into the beam buffer. */
    private record Draw(VertexConsumer out, PoseStack.Pose pose, Vec3 camera) {
        /**
         * A ribbon through {@code points}, turned to face the camera at every sample, with a
         * half-width and opacity per sample.
         */
        void ribbon(Vec3[] points, float[] halfWidth, int argb, float[] alpha) {
            int count = points.length;
            Vec3[] sides = new Vec3[count];
            for (int i = 0; i < count; i++) {
                Vec3 tangent = points[Math.min(i + 1, count - 1)]
                    .subtract(points[Math.max(i - 1, 0)]);
                Vec3 side = tangent.cross(camera.subtract(points[i]));
                double size = side.length();
                sides[i] = size < 1.0e-9 ? Vec3.ZERO : side.scale(halfWidth[i] / size);
            }
            int red = (argb >> 16) & 255;
            int green = (argb >> 8) & 255;
            int blue = argb & 255;
            for (int i = 0; i + 1 < count; i++) {
                if (alpha[i] <= 0.0f && alpha[i + 1] <= 0.0f) {
                    continue;
                }
                int a0 = alphaByte(alpha[i]);
                int a1 = alphaByte(alpha[i + 1]);
                vertex(points[i].subtract(sides[i]), red, green, blue, a0);
                vertex(points[i].add(sides[i]), red, green, blue, a0);
                vertex(points[i + 1].add(sides[i + 1]), red, green, blue, a1);
                vertex(points[i + 1].subtract(sides[i + 1]), red, green, blue, a1);
            }
        }

        /** A small square at {@code at} facing the camera. */
        void billboard(Vec3 at, float halfSize, int argb, float alpha) {
            Vec3 view = camera.subtract(at);
            Vec3 reference = Math.abs(view.normalize().y) > 0.95
                ? new Vec3(1, 0, 0)
                : new Vec3(0, 1, 0);
            Vec3 right = view.cross(reference).normalize().scale(halfSize);
            Vec3 up = right.cross(view).normalize().scale(halfSize);
            int red = (argb >> 16) & 255;
            int green = (argb >> 8) & 255;
            int blue = argb & 255;
            int a = alphaByte(alpha);
            vertex(at.subtract(right).subtract(up), red, green, blue, a);
            vertex(at.add(right).subtract(up), red, green, blue, a);
            vertex(at.add(right).add(up), red, green, blue, a);
            vertex(at.subtract(right).add(up), red, green, blue, a);
        }

        /** One position-colour vertex. */
        private void vertex(Vec3 at, int red, int green, int blue, int alpha) {
            out.addVertex(pose, (float) at.x, (float) at.y, (float) at.z)
                .setColor(red, green, blue, alpha);
        }

        /** Converts 0..1 opacity to a colour byte. */
        private static int alphaByte(float alpha) {
            return Mth.clamp((int) (alpha * 255.0f), 0, 255);
        }
    }
}
