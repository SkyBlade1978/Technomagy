package zone.moddev.mc.technomagy.machine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import net.minecraft.network.chat.Component;

import zone.moddev.mc.technomagy.mana.ManaPacket;
import zone.moddev.mc.technomagy.mana.ManaType;
import zone.moddev.mc.technomagy.mana.SpellStream;
import zone.moddev.mc.technomagy.mana.SpellStreamShape;

/**
 * What a mana link carries (D88): packets built exactly like this filter's construction, at any
 * amount.
 *
 * <p>
 * A filter is either a <b>pure type</b>, set by choosing an element or Raw in the focus's selector
 * (D73), or a <b>compound</b>, imprinted from a flowing beam or taken from the hotbar's selected
 * spell. Both are a {@link SpellStreamShape}; a pure type is just a single-leaf shape. Matching
 * compares shapes, so amounts are ignored and pure reinforcement collapses (D32).
 *
 * <p>
 * Immutable and thread-safe.
 *
 * @param shape
 *            the construction a packet must have to pass
 */
public record LinkFilter(SpellStreamShape<ManaType> shape) {
    /** The preorder name written for a merge node; element names are {@link ManaType}'s. */
    private static final String MERGE = "merge";

    /**
     * Saves a filter as its preorder walk of names, such as {@code ["merge", "fire", "water"]}.
     * Decoding rejects unknown names and any walk that is not one complete, collapsed tree.
     */
    public static final Codec<LinkFilter> CODEC = Codec.STRING.listOf().comapFlatMap(
        LinkFilter::decode,
        filter -> {
            List<String> names = new ArrayList<>();
            for (Optional<ManaType> entry : filter.shape.preorder()) {
                names.add(entry.map(ManaType::getSerializedName).orElse(MERGE));
            }
            return names;
        });

    /** Rejects a null shape. */
    public LinkFilter {
        Objects.requireNonNull(shape, "shape");
    }

    /**
     * A pure-type filter, from the focus's element selection (D73, D88).
     *
     * @param type
     *            the element or Raw
     * @return a filter passing only pure packets of that type
     */
    public static LinkFilter pure(ManaType type) {
        return new LinkFilter(SpellStreamShape.leaf(type));
    }

    /**
     * A compound filter matching a stream's construction, as imprinted or taken from a spell.
     *
     * @param stream
     *            the stream whose construction to match
     * @return the filter
     */
    public static LinkFilter of(SpellStream<ManaType> stream) {
        return new LinkFilter(SpellStreamShape.of(stream));
    }

    /**
     * Whether a packet may travel a link with this filter.
     *
     * @param packet
     *            the packet
     * @return true when the packet's construction equals this filter's
     */
    public boolean admits(ManaPacket packet) {
        return shape.equals(packet.shape());
    }

    /**
     * The type of a pure filter, which is what a link from a flat source draws (D89).
     *
     * @return the type, or empty for a compound filter
     */
    public Optional<ManaType> pureType() {
        return shape.pureElement();
    }

    /**
     * The filter for players: a mana type's name, or the construction for a compound.
     *
     * @return a display component
     */
    public Component describe() {
        return pureType()
            .<Component>map(type -> Component.translatable(
                "mana.technomagy." + type.getSerializedName()))
            .orElseGet(() -> Component.literal(shape.toString()));
    }

    /** Decodes a preorder walk of names, reporting the exact problem on failure. */
    private static DataResult<LinkFilter> decode(List<String> names) {
        List<Optional<ManaType>> walk = new ArrayList<>(names.size());
        for (String name : names) {
            if (MERGE.equals(name)) {
                walk.add(Optional.empty());
                continue;
            }
            Optional<ManaType> type = Arrays.stream(ManaType.values())
                .filter(candidate -> candidate.getSerializedName().equals(name))
                .findFirst();
            if (type.isEmpty()) {
                return DataResult.error(() -> "Unknown mana type in link filter: " + name);
            }
            walk.add(type);
        }
        try {
            return DataResult.success(
                new LinkFilter(SpellStreamShape.fromPreorder(ManaType.class, walk)));
        } catch (IllegalArgumentException malformed) {
            return DataResult.error(() -> "Malformed link filter " + names + ": "
                + malformed.getMessage());
        }
    }
}
