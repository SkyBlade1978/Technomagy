package zone.moddev.mc.technomagy.mana;

import java.util.Optional;

/**
 * Something that stores, accepts or supplies mana (D24): the mana counterpart of NeoForge's {@code
 * IEnergyStorage}. Get one from a block with {@link ManaCapabilities#BLOCK}.
 *
 * <p>
 * A handler has no transfer rate of its own. Rate belongs to the link moving mana between two
 * handlers, which limits each transfer (see {@link Mana#limit}). A handler only decides which types
 * it accepts or supplies, and how much fits.
 */
public interface IManaHandler {
    /**
     * Everything currently stored.
     *
     * @return the stored mana; {@link Mana#EMPTY} when empty
     */
    Mana getStored();

    /**
     * The most mana, of all types together, this can hold.
     *
     * @return the capacity, at least 0
     */
    int getCapacity();

    /**
     * Offers mana to this handler.
     *
     * @param offered
     *            the mana offered
     * @param simulate
     *            if true, only report what would be accepted
     * @return the part of {@code offered} accepted; empty if none
     */
    Mana receive(Mana offered, boolean simulate);

    /**
     * Takes mana from this handler.
     *
     * @param wanted
     *            the most of each type to take
     * @param simulate
     *            if true, only report what would be taken
     * @return what was taken, never more of a type than {@code wanted} asked for; empty if none
     */
    Mana extract(Mana wanted, boolean simulate);

    /**
     * Offers one whole structured packet (D89). A packet is never cut, so it is accepted entirely
     * or not at all.
     *
     * <p>
     * The default suits flat handlers, which keep only amounts: the packet is flattened with
     * {@link ManaPacket#toFlat()} (converting D47 Raw to the flat model's Raw explicitly) and
     * accepted only if {@link #receive} would take all of it. The merge structure is lost, as a
     * flat store cannot hold it. Handlers that keep structure, such as directors, override this.
     *
     * @param packet
     *            the packet offered
     * @param simulate
     *            if true, only report whether it would be accepted
     * @return true if the whole packet was (or would be) accepted
     */
    default boolean receivePacket(ManaPacket packet, boolean simulate) {
        Mana flat = packet.toFlat();
        if (!receive(flat, true).equals(flat)) {
            return false;
        }
        if (!simulate && !receive(flat, false).equals(flat)) {
            throw new IllegalStateException(
                getClass().getName() + " accepted " + flat + " when simulated but not for real");
        }
        return true;
    }

    /**
     * Takes one single-element packet of exactly {@code units} units of {@code element}, as a link
     * from a flat source draws it (D89). Raw is counted per D47: one packet unit of Raw takes
     * {@value Mana#RAW_PER_SET} flat Raw units.
     *
     * <p>
     * The default suits flat handlers: it takes the amount through {@link #extract} only if all of
     * it is available. Handlers that hold structured packets override this or return empty.
     *
     * @param element
     *            the element to take, Raw included
     * @param units
     *            positive packet units to take
     * @param simulate
     *            if true, only report what would be taken
     * @return the packet taken, or empty if the full amount is not available
     * @throws IllegalArgumentException
     *             if {@code units} is not positive
     */
    default Optional<ManaPacket> extractPacket(ManaType element, int units, boolean simulate) {
        if (units <= 0) {
            throw new IllegalArgumentException("Packet units must be positive: " + units);
        }
        Mana wanted = Mana.of(element,
            element == ManaType.RAW ? Math.multiplyExact(units, Mana.RAW_PER_SET) : units);
        if (!extract(wanted, true).equals(wanted)) {
            return Optional.empty();
        }
        if (!simulate && !extract(wanted, false).equals(wanted)) {
            throw new IllegalStateException(
                getClass().getName() + " offered " + wanted + " when simulated but not for real");
        }
        return Optional.of(ManaPacket.leaf(element, units));
    }
}
