package zone.moddev.mc.technomagy.machine;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import zone.moddev.mc.technomagy.mana.ManaPacket;
import zone.moddev.mc.technomagy.mana.ManaType;

/**
 * What a client needs to draw a flowing beam (D91): how much of each flavor the packet in flight
 * carries, and whether it holds a life &amp; death pair. Clients never receive packets themselves
 * (D92), only this summary.
 *
 * <p>
 * Raw counts as one unit of every flavor (D47), so a Raw packet draws six equal strands (D15).
 *
 * <p>
 * Immutable: the array is copied in and out.
 *
 * @param flavorUnits
 *            units of each flavor, indexed like {@link ManaType#FLAVORS}; never negative, not all
 *            zero
 * @param lifeAndDeath
 *            whether the packet holds a direct Light + Dark prism merge (D35, D64), drawn as a
 *            Light and a Dark strand wound around each other
 */
public record BeamVisual(int[] flavorUnits, boolean lifeAndDeath) {
    /** The summary as saved, before its rules are checked. */
    private record Wire(List<Integer> units, boolean lifeAndDeath) {
    }

    private static final Codec<Wire> WIRE = RecordCodecBuilder.create(
        instance -> instance.group(
            Codec.INT.listOf().fieldOf("units").forGetter(Wire::units),
            Codec.BOOL.fieldOf("life_and_death").forGetter(Wire::lifeAndDeath))
            .apply(instance, Wire::new));

    /**
     * Saves the summary for the client update tag. Decoding reports a wrong count list as an error
     * instead of throwing.
     */
    public static final Codec<BeamVisual> CODEC = WIRE.flatXmap(
        wire -> {
            try {
                return DataResult.success(new BeamVisual(
                    wire.units().stream().mapToInt(Integer::intValue).toArray(),
                    wire.lifeAndDeath()));
            } catch (IllegalArgumentException invalid) {
                return DataResult.error(invalid::getMessage);
            }
        },
        visual -> DataResult.success(new Wire(
            Arrays.stream(visual.flavorUnits).boxed().toList(), visual.lifeAndDeath)));

    /**
     * Checks and copies the units.
     *
     * @throws IllegalArgumentException
     *             if there are not six counts, any is negative, or all are zero
     */
    public BeamVisual {
        if (flavorUnits.length != ManaType.FLAVORS.size()) {
            throw new IllegalArgumentException("Need one count per flavor: "
                + Arrays.toString(flavorUnits));
        }
        boolean any = false;
        for (int units : flavorUnits) {
            if (units < 0) {
                throw new IllegalArgumentException("Negative flavor units: "
                    + Arrays.toString(flavorUnits));
            }
            any |= units > 0;
        }
        if (!any) {
            throw new IllegalArgumentException("A beam carries at least one unit");
        }
        flavorUnits = flavorUnits.clone();
    }

    /**
     * Summarizes a packet for drawing.
     *
     * @param packet
     *            the packet in flight
     * @return its visual summary
     */
    public static BeamVisual of(ManaPacket packet) {
        // A life & death pair is a merge whose two children are a pure Light and a pure Dark input
        // (D64). In the shape's preorder, that is a merge marker followed directly by those two
        // leaves, in either order.
        List<Optional<ManaType>> walk = packet.shape().preorder();
        boolean pair = false;
        for (int i = 0; i + 2 < walk.size() && !pair; i++) {
            if (walk.get(i).isEmpty()) {
                Optional<ManaType> first = walk.get(i + 1);
                Optional<ManaType> second = walk.get(i + 2);
                pair = first.isPresent() && second.isPresent()
                    && (first.get() == ManaType.LIGHT && second.get() == ManaType.DARK
                        || first.get() == ManaType.DARK && second.get() == ManaType.LIGHT);
            }
        }
        return new BeamVisual(packet.flavorUnits(), pair);
    }

    /**
     * The units of each flavor.
     *
     * @return a copy, indexed like {@link ManaType#FLAVORS}
     */
    @Override
    public int[] flavorUnits() {
        return flavorUnits.clone();
    }

    /** Equal when the counts and the pair flag are equal. */
    @Override
    public boolean equals(Object other) {
        return other instanceof BeamVisual visual
            && visual.lifeAndDeath == lifeAndDeath
            && Arrays.equals(visual.flavorUnits, flavorUnits);
    }

    /** Consistent with {@link #equals}. */
    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(flavorUnits) + Boolean.hashCode(lifeAndDeath);
    }

    /** The counts and pair flag, for logs. */
    @Override
    public String toString() {
        return "BeamVisual" + Arrays.toString(flavorUnits) + (lifeAndDeath ? "+pair" : "");
    }
}
