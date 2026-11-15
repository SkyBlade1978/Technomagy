package zone.moddev.mc.technomagy.mana;

import java.util.List;
import java.util.Optional;

/** Standalone checks for D88's amount-free construction matching and D32's pure collapse. */
final class SpellStreamShapeChecks {
    /** Test-only elements keep these checks independent of Minecraft initialization. */
    private enum Element {
        FIRE, EARTH, AIR, WATER, LIGHT, DARK
    }

    private SpellStreamShapeChecks() {
    }

    /** Runs every shape check; any failure throws. */
    public static void main(String[] args) {
        var fire = SpellStream.element(Element.FIRE, 1);
        var twoFire = SpellStream.element(Element.FIRE, 2);
        var water = SpellStream.element(Element.WATER, 1);
        var earth = SpellStream.element(Element.EARTH, 1);
        var air = SpellStream.element(Element.AIR, 1);

        // Amounts are ignored: 3 Fire + 1 Water matches a 2 Fire + 1 Water construction.
        var direct = SpellStream.merge(twoFire, water);
        require(SpellStreamShape.of(direct)
            .matches(SpellStream.merge(SpellStream.element(Element.FIRE, 3), water)));

        // D32: a Fire + Fire prism merge is pure Fire, so it matches a 2-Fire source input.
        var mergedFire = SpellStream.merge(SpellStream.merge(fire, fire), water);
        require(SpellStreamShape.of(direct).equals(SpellStreamShape.of(mergedFire)));
        require(SpellStreamShape.of(SpellStream.merge(fire, fire)).equals(
            SpellStreamShape.leaf(Element.FIRE)));

        // Construction matters: 2 Fire + 1 Water is not Steam merged with Fire.
        var steamThenFire = SpellStream.merge(SpellStream.merge(fire, water), fire);
        require(!SpellStreamShape.of(direct).equals(SpellStreamShape.of(steamThenFire)));

        // Slot order matters (D52): Erode -> Forge differs from Forge -> Erode.
        var erode = SpellStream.merge(earth, air);
        var forge = SpellStream.merge(fire, earth);
        require(!SpellStreamShape.of(SpellStream.merge(erode, forge))
            .equals(SpellStreamShape.of(SpellStream.merge(forge, erode))));
        require(!SpellStreamShape.of(SpellStream.merge(fire, water))
            .equals(SpellStreamShape.of(SpellStream.merge(water, fire))));

        // Pure shapes report their element; merges report none.
        require(SpellStreamShape.leaf(Element.DARK).pureElement().orElseThrow() == Element.DARK);
        require(SpellStreamShape.of(erode).pureElement().isEmpty());
        require(!SpellStreamShape.of(erode).isPure());

        // Preorder round trip, including the worked example in the class Javadoc.
        var shape = SpellStreamShape.of(steamThenFire);
        require(shape.preorder().equals(List.of(Optional.empty(), Optional.empty(),
            Optional.of(Element.FIRE), Optional.of(Element.WATER), Optional.of(Element.FIRE))));
        require(SpellStreamShape.fromPreorder(Element.class, shape.preorder()).equals(shape));
        require(shape.toString().equals("((FIRE+WATER)+FIRE)"));

        // Malformed and uncollapsed walks are rejected, never repaired.
        rejects(List.of(Optional.empty(), Optional.of(Element.FIRE)));
        rejects(List.of(Optional.of(Element.FIRE), Optional.of(Element.WATER)));
        rejects(List.of());
        rejects(List.of(Optional.empty(), Optional.of(Element.FIRE), Optional.of(Element.FIRE)));

        // Long prism chains neither recurse nor go quadratic.
        var deep = erode;
        for (int i = 0; i < 20000; i++) {
            deep = SpellStream.merge(deep, i % 2 == 0 ? water : fire);
        }
        var deepShape = SpellStreamShape.of(deep);
        require(deepShape.preorder().size() == 2 * 20000 + 3);
        require(SpellStreamShape.fromPreorder(Element.class, deepShape.preorder())
            .equals(deepShape));
        System.out.println("SpellStreamShapeChecks passed");
    }

    /** Requires {@link SpellStreamShape#fromPreorder} to reject a walk. */
    private static void rejects(List<Optional<Element>> walk) {
        try {
            SpellStreamShape.fromPreorder(Element.class, walk);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Accepted malformed shape walk " + walk);
    }

    /** Fails loudly; these checks run without a test framework. */
    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError("Shape check failed");
        }
    }
}
