package zone.moddev.mc.technomagy.mana;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One whole structured stream travelling a mana link (D37, D89): the unit links move, queue, and
 * hand to prisms. A packet is never cut; a link moves whole packets per pulse.
 *
 * <p>
 * <b>Quantities follow D47:</b> a Raw input of {@code n} units holds {@code n} of each of the six
 * flavors. The legacy flat {@link Mana} model stores Raw as {@value Mana#RAW_PER_SET} units per set
 * instead, so {@link #toFlat()} and {@link IManaHandler#extractPacket} convert explicitly at that
 * boundary (D89).
 *
 * <p>
 * <b>Depth limit.</b> A packet is saved as nested NBT, one compound per merge level, and Minecraft
 * refuses to read NBT nested deeper than 512. A packet deeper than {@value #MAX_DEPTH} merges could
 * be saved but never loaded again, so it is rejected at construction; a prism refuses a merge that
 * would exceed it. This is a storage limit, not a gameplay rule: the design has not yet settled a
 * maximum chain depth (P2).
 *
 * <p>
 * Instances are immutable and thread-safe. The shape is computed once, on first use.
 */
public final class ManaPacket {
    /** The deepest merge nesting a packet may have, so it always fits NBT's read depth limit. */
    public static final int MAX_DEPTH = 200;

    private static final Codec<ManaPacket> LEAF = RecordCodecBuilder.create(
        instance -> instance.group(
            ManaType.CODEC.fieldOf("element").forGetter(packet -> packet.stream.element()),
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("units")
                .forGetter(packet -> packet.stream.units()))
            .apply(instance, ManaPacket::leaf));

    /**
     * Saves a packet as nested compounds: {@code {element: "fire", units: 2}} for an input, or
     * {@code {primary: {...}, secondary: {...}}} for a merge. Decoding rejects non-positive units,
     * unknown element names, and trees deeper than {@link #MAX_DEPTH}.
     */
    public static final Codec<ManaPacket> CODEC = Codec.recursive(
        "ManaPacket",
        self -> Codec.either(LEAF, mergeCodec(self)).xmap(
            either -> either.map(leaf -> leaf, merge -> merge),
            packet -> packet.isElement()
                ? Either.<ManaPacket, ManaPacket>left(packet)
                : Either.<ManaPacket, ManaPacket>right(packet)));

    private final SpellStream<ManaType> stream;
    private final int depth;
    private volatile SpellStreamShape<ManaType> shape;

    /** Wraps a stream after checking its depth; only the factories call this. */
    private ManaPacket(SpellStream<ManaType> stream) {
        this.stream = Objects.requireNonNull(stream, "stream");
        this.depth = depthOf(stream);
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                "Mana packet nests " + depth + " merges; at most " + MAX_DEPTH + " can be saved");
        }
    }

    /**
     * A packet carrying an existing stream.
     *
     * @param stream
     *            the stream
     * @return the packet
     * @throws IllegalArgumentException
     *             if the stream nests more than {@link #MAX_DEPTH} merges
     */
    public static ManaPacket of(SpellStream<ManaType> stream) {
        return new ManaPacket(stream);
    }

    /**
     * A single-element packet, such as one drawn from a flat source (D89).
     *
     * @param element
     *            the element, Raw included
     * @param units
     *            positive whole units, Raw counted per D47
     * @return the packet
     * @throws IllegalArgumentException
     *             if {@code units} is not positive
     */
    public static ManaPacket leaf(ManaType element, int units) {
        return new ManaPacket(SpellStream.element(element, units));
    }

    /**
     * The merge of two packets, primary first (D20, D52).
     *
     * @param primary
     *            the primary slot's packet
     * @param secondary
     *            the secondary slot's packet
     * @return the merged packet
     * @throws IllegalArgumentException
     *             if the merge would nest more than {@link #MAX_DEPTH} merges
     */
    public static ManaPacket merge(ManaPacket primary, ManaPacket secondary) {
        return new ManaPacket(SpellStream.merge(primary.stream, secondary.stream));
    }

    /**
     * Whether merging two packets would stay within {@link #MAX_DEPTH}.
     *
     * @param primary
     *            the primary packet
     * @param secondary
     *            the secondary packet
     * @return true if {@link #merge} would succeed
     */
    public static boolean canMerge(ManaPacket primary, ManaPacket secondary) {
        return Math.max(primary.depth, secondary.depth) + 1 <= MAX_DEPTH;
    }

    /**
     * Builds the merge-node codec, given the recursive packet codec for its two children. Decoding
     * rejects a merge deeper than {@link #MAX_DEPTH}; encoding rejects a single-element packet,
     * which {@link #CODEC} never routes here.
     */
    private static Codec<ManaPacket> mergeCodec(Codec<ManaPacket> self) {
        return RecordCodecBuilder.<Pair<ManaPacket, ManaPacket>>create(
            instance -> instance.group(
                self.fieldOf("primary").forGetter(Pair::getFirst),
                self.fieldOf("secondary").forGetter(Pair::getSecond))
                .apply(instance, Pair::of))
            .flatXmap(
                pair -> canMerge(pair.getFirst(), pair.getSecond())
                    ? DataResult.success(merge(pair.getFirst(), pair.getSecond()))
                    : DataResult.error(
                        () -> "Mana packet nests more than " + MAX_DEPTH + " merges"),
                packet -> {
                    if (packet.isElement()) {
                        return DataResult.error(() -> "Not a merge: " + packet);
                    }
                    List<ManaPacket> parts = packet.split();
                    return DataResult.success(Pair.of(parts.get(0), parts.get(1)));
                });
    }

    /** Counts merge levels on the deepest path, iteratively. */
    private static int depthOf(SpellStream<ManaType> root) {
        int deepest = 0;
        ArrayDeque<SpellStream<ManaType>> nodes = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        nodes.push(root);
        depths.push(0);
        while (!nodes.isEmpty()) {
            SpellStream<ManaType> node = nodes.pop();
            int depth = depths.pop();
            deepest = Math.max(deepest, depth);
            if (!node.isElement()) {
                for (SpellStream<ManaType> part : node.split()) {
                    nodes.push(part);
                    depths.push(depth + 1);
                }
            }
        }
        return deepest;
    }

    /**
     * The carried stream.
     *
     * @return the immutable stream
     */
    public SpellStream<ManaType> stream() {
        return stream;
    }

    /**
     * The packet's construction, as filters match it (D88).
     *
     * @return the amount-free shape
     */
    public SpellStreamShape<ManaType> shape() {
        SpellStreamShape<ManaType> result = shape;
        if (result == null) {
            result = SpellStreamShape.of(stream);
            shape = result;
        }
        return result;
    }

    /**
     * Whether this is a single input rather than a merge.
     *
     * @return true for a single-element packet
     */
    public boolean isElement() {
        return stream.isElement();
    }

    /**
     * Undoes the most recent merge (D26, D37).
     *
     * @return the primary and secondary packets, in that order
     * @throws IllegalStateException
     *             if this is a single-element packet
     */
    public List<ManaPacket> split() {
        List<SpellStream<ManaType>> parts = stream.split();
        return List.of(new ManaPacket(parts.get(0)), new ManaPacket(parts.get(1)));
    }

    /**
     * The units of each of the six flavors the packet carries, with each Raw unit counted as one of
     * every flavor (D47).
     *
     * @return a fresh array indexed like {@link ManaType#FLAVORS}
     * @throws ArithmeticException
     *             if a total overflows an {@code int}
     */
    public int[] flavorUnits() {
        int[] units = new int[ManaType.FLAVORS.size()];
        forEachLeaf((element, amount) -> {
            if (element == ManaType.RAW) {
                for (int i = 0; i < units.length; i++) {
                    units[i] = Math.addExact(units[i], amount);
                }
            } else {
                units[element.ordinal()] = Math.addExact(units[element.ordinal()], amount);
            }
        });
        return units;
    }

    /**
     * The packet as flat legacy mana, for handlers that only store amounts (D89). Each D47 Raw unit
     * becomes {@value Mana#RAW_PER_SET} flat Raw units; flavors convert one for one.
     *
     * @return the flat totals
     * @throws ArithmeticException
     *             if a total overflows an {@code int}
     */
    public Mana toFlat() {
        Mana[] total = {Mana.EMPTY};
        forEachLeaf((element, amount) -> total[0] = total[0].plus(Mana.of(element,
            element == ManaType.RAW ? Math.multiplyExact(amount, Mana.RAW_PER_SET) : amount)));
        return total[0];
    }

    /** Visits every input once, iteratively. */
    private void forEachLeaf(LeafVisitor visitor) {
        ArrayDeque<SpellStream<ManaType>> nodes = new ArrayDeque<>();
        nodes.push(stream);
        while (!nodes.isEmpty()) {
            SpellStream<ManaType> node = nodes.pop();
            if (node.isElement()) {
                visitor.visit(node.element(), node.units());
            } else {
                node.split().forEach(nodes::push);
            }
        }
    }

    /** A packet equals another carrying the same stream instance. */
    @Override
    public boolean equals(Object other) {
        return other instanceof ManaPacket packet && packet.stream == stream;
    }

    /** Consistent with {@link #equals}. */
    @Override
    public int hashCode() {
        return System.identityHashCode(stream);
    }

    /**
     * The shape and total flavor units, for logs.
     *
     * @return a readable summary
     */
    @Override
    public String toString() {
        return "ManaPacket" + shape() + java.util.Arrays.toString(flavorUnits());
    }

    /** Receives one input of a packet. */
    @FunctionalInterface
    private interface LeafVisitor {
        /** Visits one input's element and units. */
        void visit(ManaType element, int units);
    }
}
