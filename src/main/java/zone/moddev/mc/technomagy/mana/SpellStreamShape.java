package zone.moddev.mc.technomagy.mana;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The amount-free construction of a {@link SpellStream}: what a mana link filter matches (D88).
 *
 * <p>
 * Two streams have the same shape when they were built the same way: the same merge tree with the
 * same element in each slot, whatever the unit amounts. Primary and secondary are distinct slots
 * (D52), so {@code Erode -> Forge} and {@code Forge -> Erode} differ, and 2 Fire merged with 1
 * Water differs from Steam merged with Fire.
 *
 * <p>
 * <b>Pure reinforcement collapses (D32).</b> A subtree whose every leaf is the same element is
 * still that pure element, only stronger, so it has the shape of a single leaf. A 2-Fire input from
 * a source and a Fire + Fire prism merge therefore have the same shape. The collapse applies at
 * every depth: {@code (Fire + Fire) + Water} has the shape {@code Fire + Water}.
 *
 * <p>
 * <b>Encoding.</b> The shape is stored as its preorder walk: {@code -1} for a merge, followed by
 * its primary and then its secondary subtree, or the element's ordinal for a leaf. Every binary
 * tree has exactly one such walk and every valid walk decodes to one tree, so equality and hashing
 * are plain array comparisons and never recurse. A worked example, with Fire = 0 and Water = 3:
 *
 * <pre>
 *        merge                 preorder:  -1, -1, 0, 3, 0
 *       /     \
 *    merge    Fire             (Steam merged with Fire)
 *    /   \
 *  Fire  Water
 * </pre>
 *
 * <p>
 * Building a shape takes two linear passes with explicit stacks, as {@link SpellStream} does, so
 * long prism chains cannot exhaust the call stack: a postorder pass marks the pure subtrees, then a
 * preorder pass writes the walk, emitting each pure subtree as one leaf. Instances are immutable
 * and thread-safe.
 *
 * @param <E>
 *            the element enum of the streams this shape describes
 */
public final class SpellStreamShape<E extends Enum<E>> {
    /** Preorder marker for a merge node. */
    private static final int MERGE = -1;

    private final Class<E> type;
    private final int[] preorder;
    private final int hash;

    /** Stores a validated preorder walk; only the factories call this. */
    private SpellStreamShape(Class<E> type, int[] preorder) {
        this.type = type;
        this.preorder = preorder;
        this.hash = 31 * type.hashCode() + Arrays.hashCode(preorder);
    }

    /**
     * The shape of a pure stream of one element, such as a pure-type link filter.
     *
     * @param <E>
     *            element enum
     * @param element
     *            the element
     * @return a single-leaf shape
     * @throws NullPointerException
     *             if {@code element} is null
     */
    public static <E extends Enum<E>> SpellStreamShape<E> leaf(E element) {
        Objects.requireNonNull(element, "element");
        return new SpellStreamShape<>(element.getDeclaringClass(), new int[]{element.ordinal()});
    }

    /**
     * The shape of a stream, collapsing pure single-element subtrees (D32).
     *
     * @param <E>
     *            element enum
     * @param stream
     *            the stream to describe
     * @return its shape
     * @throws NullPointerException
     *             if {@code stream} is null
     */
    public static <E extends Enum<E>> SpellStreamShape<E> of(SpellStream<E> stream) {
        Objects.requireNonNull(stream, "stream");
        // Pass 1, postorder: record which nodes are pure (every leaf the same element). Streams
        // share subtrees by identity, so the record is keyed by identity, not equality.
        IdentityHashMap<SpellStream<E>, E> pure = new IdentityHashMap<>();
        ArrayDeque<Frame<E>> stack = new ArrayDeque<>();
        stack.push(new Frame<>(stream, false));
        Class<E> type = null;
        while (!stack.isEmpty()) {
            Frame<E> frame = stack.pop();
            SpellStream<E> node = frame.node();
            if (node.isElement()) {
                type = node.element().getDeclaringClass();
                pure.put(node, node.element());
            } else if (!frame.expanded()) {
                List<SpellStream<E>> parts = node.split();
                stack.push(new Frame<>(node, true));
                stack.push(new Frame<>(parts.get(1), false));
                stack.push(new Frame<>(parts.get(0), false));
            } else {
                List<SpellStream<E>> parts = node.split();
                E primary = pure.get(parts.get(0));
                if (primary != null && primary == pure.get(parts.get(1))) {
                    pure.put(node, primary);
                }
            }
        }
        // Pass 2, preorder: emit merges as MERGE and any pure subtree as a single leaf (D32).
        IntList walk = new IntList();
        ArrayDeque<SpellStream<E>> pending = new ArrayDeque<>();
        pending.push(stream);
        while (!pending.isEmpty()) {
            SpellStream<E> node = pending.pop();
            E element = pure.get(node);
            if (element != null) {
                walk.add(element.ordinal());
            } else {
                List<SpellStream<E>> parts = node.split();
                walk.add(MERGE);
                pending.push(parts.get(1));
                pending.push(parts.get(0));
            }
        }
        return new SpellStreamShape<>(type, walk.toArray());
    }

    /**
     * Rebuilds a shape from its preorder walk, as saved by {@link #preorder()}.
     *
     * @param <E>
     *            element enum
     * @param type
     *            the element enum class
     * @param walk
     *            empty entries for merges, present entries for leaves, in preorder
     * @return the shape
     * @throws IllegalArgumentException
     *             if the walk is not exactly one complete binary tree, or if it is not collapsed (a
     *             merge whose leaves are all one element is saved as a leaf, never as a merge)
     */
    public static <E extends Enum<E>> SpellStreamShape<E> fromPreorder(
        Class<E> type, List<Optional<E>> walk) {
        Objects.requireNonNull(type, "type");
        int[] preorder = new int[walk.size()];
        // Each merge needs two more subtrees; each leaf completes one. A complete tree ends with
        // exactly zero subtrees pending, and never runs out of pending slots before the end.
        int pending = 1;
        for (int i = 0; i < walk.size(); i++) {
            if (pending == 0) {
                throw new IllegalArgumentException("Shape walk continues past a complete tree");
            }
            Optional<E> entry = walk.get(i);
            if (entry.isPresent()) {
                preorder[i] = entry.get().ordinal();
                pending--;
            } else {
                preorder[i] = MERGE;
                pending++;
            }
        }
        if (pending != 0) {
            throw new IllegalArgumentException("Shape walk ends before the tree is complete");
        }
        SpellStreamShape<E> shape = new SpellStreamShape<>(type, preorder);
        if (!shape.equals(collapse(shape))) {
            throw new IllegalArgumentException(
                "Shape walk holds a pure merge that should be a single input (D32)");
        }
        return shape;
    }

    /** Rebuilds a shape's stream with one unit per leaf, then re-derives its collapsed shape. */
    private static <E extends Enum<E>> SpellStreamShape<E> collapse(SpellStreamShape<E> shape) {
        E[] constants = shape.type.getEnumConstants();
        ArrayDeque<SpellStream<E>> built = new ArrayDeque<>();
        // Walk the preorder backwards: a leaf pushes its stream, a merge pops its two children.
        for (int i = shape.preorder.length - 1; i >= 0; i--) {
            int code = shape.preorder[i];
            if (code == MERGE) {
                SpellStream<E> primary = built.pop();
                SpellStream<E> secondary = built.pop();
                built.push(SpellStream.merge(primary, secondary));
            } else {
                built.push(SpellStream.element(constants[code], 1));
            }
        }
        return of(built.pop());
    }

    /**
     * Whether a stream was built this way (D88): its shape equals this one.
     *
     * @param stream
     *            the stream to test
     * @return true when the stream's shape equals this shape
     */
    public boolean matches(SpellStream<E> stream) {
        return equals(of(stream));
    }

    /**
     * Whether this is the shape of a pure single-element stream.
     *
     * @return true for a single leaf
     */
    public boolean isPure() {
        return preorder.length == 1;
    }

    /**
     * The element of a pure shape.
     *
     * @return the element, or empty when this shape is a merge
     */
    public Optional<E> pureElement() {
        return isPure() ? Optional.of(type.getEnumConstants()[preorder[0]]) : Optional.empty();
    }

    /**
     * This shape's preorder walk, for saving: empty for a merge, the element for a leaf.
     *
     * @return an unmodifiable list, round-tripped by {@link #fromPreorder}
     */
    public List<Optional<E>> preorder() {
        E[] constants = type.getEnumConstants();
        List<Optional<E>> walk = new ArrayList<>(preorder.length);
        for (int code : preorder) {
            walk.add(code == MERGE ? Optional.empty() : Optional.of(constants[code]));
        }
        return Collections.unmodifiableList(walk);
    }

    /** Equal when both describe the same construction over the same element enum. */
    @Override
    public boolean equals(Object other) {
        return other instanceof SpellStreamShape<?> shape
            && shape.type == type
            && Arrays.equals(shape.preorder, preorder);
    }

    /** Consistent with {@link #equals}; computed once at construction. */
    @Override
    public int hashCode() {
        return hash;
    }

    /**
     * A readable form for logs and messages, such as {@code ((FIRE+WATER)+FIRE)}.
     *
     * @return the shape as nested sums
     */
    @Override
    public String toString() {
        E[] constants = type.getEnumConstants();
        ArrayDeque<String> built = new ArrayDeque<>();
        for (int i = preorder.length - 1; i >= 0; i--) {
            int code = preorder[i];
            if (code == MERGE) {
                String primary = built.pop();
                String secondary = built.pop();
                built.push("(" + primary + "+" + secondary + ")");
            } else {
                built.push(constants[code].name());
            }
        }
        return built.pop();
    }

    /** One pending visit: a node, and whether its children have been scheduled already. */
    private record Frame<E extends Enum<E>>(SpellStream<E> node, boolean expanded) {
    }

    /** A growable int array, so the preorder walk is built without boxing. Not thread-safe. */
    private static final class IntList {
        private int[] values = new int[8];
        private int size;

        /** Appends one value, doubling the backing array when full. */
        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        /** Copies the values added so far into an exact-length array. */
        int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
