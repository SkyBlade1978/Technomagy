package zone.moddev.mc.technomagy.machine;

import net.minecraft.world.item.Item;

/**
 * A rate upgrade for one mana link (D90): the minor, greater, and grand lenses raise a link to 2,
 * 4, and 8 packets per pulse. Each needs the tier below it fitted first. A player fits one by using
 * it while looking at the beam; the client picks the beam and the server re-validates everything
 * before consuming the item (see {@link FitLensPayload}).
 *
 * <p>
 * The lenses have no recipes yet and come from the creative tab; recipes are to be decided with the
 * textures (D90).
 */
public final class LinkLensItem extends Item {
    private final int tier;

    /**
     * Creates a lens.
     *
     * @param properties
     *            item properties
     * @param tier
     *            the link tier this lens fits, 1 to {@value ManaLink#MAX_TIER}
     * @throws IllegalArgumentException
     *             if the tier is out of range
     */
    public LinkLensItem(Properties properties, int tier) {
        super(properties);
        if (tier < 1 || tier > ManaLink.MAX_TIER) {
            throw new IllegalArgumentException("Lens tier must be 1 to " + ManaLink.MAX_TIER);
        }
        this.tier = tier;
    }

    /**
     * The tier a link reaches once this lens is fitted.
     *
     * @return 1 to {@value ManaLink#MAX_TIER}
     */
    public int tier() {
        return tier;
    }
}
