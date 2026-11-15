package zone.moddev.mc.technomagy.machine;

import java.util.Objects;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;

/**
 * One link as clients see it (D92): its endpoints, rate tier, whether it is paused, and, while it
 * is flowing, what it carries. The server sends these in its node's update tag only when one of
 * them changes; clients animate between updates on their own.
 *
 * <p>
 * Immutable and thread-safe.
 *
 * @param from
 *            where packets leave
 * @param to
 *            where packets arrive
 * @param tier
 *            fitted lenses, 0 to {@value ManaLink#MAX_TIER} (D90)
 * @param paused
 *            whether the line of sight is blocked (D87)
 * @param flowing
 *            the packet summary while flowing, or empty while idle (D91)
 */
public record BeamState(BlockPos from, BlockPos to, int tier, boolean paused,
    Optional<BeamVisual> flowing) {
    /** Encodes a beam for the update tag. */
    public static final Codec<BeamState> CODEC = RecordCodecBuilder.create(
        instance -> instance.group(
            BlockPos.CODEC.fieldOf("from").forGetter(BeamState::from),
            BlockPos.CODEC.fieldOf("to").forGetter(BeamState::to),
            Codec.intRange(0, ManaLink.MAX_TIER).fieldOf("tier").forGetter(BeamState::tier),
            Codec.BOOL.fieldOf("paused").forGetter(BeamState::paused),
            BeamVisual.CODEC.optionalFieldOf("flowing").forGetter(BeamState::flowing))
            .apply(instance, BeamState::new));

    /** Rejects null parts. */
    public BeamState {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(flowing, "flowing");
    }
}
