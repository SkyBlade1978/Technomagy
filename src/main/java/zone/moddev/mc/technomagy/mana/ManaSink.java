package zone.moddev.mc.technomagy.mana;

import java.util.Optional;

/**
 * Accepts every offer of mana and keeps none of it: the counterpart of {@link InfiniteManaSource},
 * for testing links. A link ending at a sink never fills, so a chain feeding one keeps flowing and
 * its beams stay active (D91). Stateless, so one instance can serve every block of a kind.
 */
public final class ManaSink implements IManaHandler {
    /**
     * Reports an always-empty store: whatever arrives is destroyed at once.
     *
     * @return {@link Mana#EMPTY}
     */
    @Override
    public Mana getStored() {
        return Mana.EMPTY;
    }

    /**
     * Reports an unlimited capacity.
     *
     * @return {@link Integer#MAX_VALUE}
     */
    @Override
    public int getCapacity() {
        return Integer.MAX_VALUE;
    }

    /**
     * Accepts all of every offer and discards it. The sink is never filled, so {@code simulate}
     * makes no difference.
     *
     * @return {@code offered}, unchanged
     */
    @Override
    public Mana receive(Mana offered, boolean simulate) {
        return offered;
    }

    /**
     * Supplies nothing.
     *
     * @return always {@link Mana#EMPTY}
     */
    @Override
    public Mana extract(Mana wanted, boolean simulate) {
        return Mana.EMPTY;
    }

    /**
     * Accepts every whole packet and discards it (D89). Overridden so a packet is never flattened:
     * the flat form of a very large packet could overflow, and a sink has no use for it anyway.
     *
     * @return always true
     */
    @Override
    public boolean receivePacket(ManaPacket packet, boolean simulate) {
        return true;
    }

    /**
     * Supplies nothing, so a link drawing from a sink stays idle.
     *
     * @return always empty
     */
    @Override
    public Optional<ManaPacket> extractPacket(ManaType element, int units,
        boolean simulate) {
        if (units <= 0) {
            throw new IllegalArgumentException("Packet units must be positive: " + units);
        }
        return Optional.empty();
    }
}
