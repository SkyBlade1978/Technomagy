package zone.moddev.mc.technomagy.mana;

/**
 * The one table of flavor colours, shared by the personal pool HUD (D75), mana beams (D91), and the
 * particles a broken node releases (D87), so a flavor looks the same everywhere.
 *
 * <p>
 * Raw has no colour of its own: it is an even mix of the six flavors (D15) and is drawn as all six
 * (D91). Stateless and thread-safe.
 */
public final class ManaColors {
    private ManaColors() {
    }

    /**
     * A flavor's colour, fully opaque. Dark is a violet charcoal so it stays visible against glass
     * and night skies.
     *
     * @param flavor
     *            one of the six flavors
     * @return the colour as {@code 0xAARRGGBB}
     * @throws IllegalArgumentException
     *             for {@link ManaType#RAW}, which is drawn as all six flavors instead
     */
    public static int argb(ManaType flavor) {
        return switch (flavor) {
            case FIRE -> 0xfff3764d;
            case EARTH -> 0xff9bc66a;
            case AIR -> 0xffb7dbdc;
            case WATER -> 0xff4ba4f4;
            case LIGHT -> 0xfff0ead6;
            case DARK -> 0xffa88ac9;
            case RAW -> throw new IllegalArgumentException(
                "Raw has no single colour; draw it as all six flavors (D15, D91)");
        };
    }
}
