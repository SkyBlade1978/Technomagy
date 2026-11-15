package zone.moddev.mc.technomagy.machine;

import java.util.Objects;

import zone.moddev.mc.technomagy.mana.ManaPacket;

/**
 * A packet a node holds, with the pulse it arrived on. A node only processes or sends packets that
 * arrived before the current pulse, so every packet moves exactly one link per pulse whatever order
 * the nodes pulse in (see {@link ManaNodeBlockEntity}).
 *
 * @param packet
 *            the packet
 * @param arrived
 *            the game time it arrived; {@link Long#MIN_VALUE} for one loaded from a save
 */
record Held(ManaPacket packet, long arrived) {
    /** Rejects a null packet. */
    Held {
        Objects.requireNonNull(packet, "packet");
    }
}
