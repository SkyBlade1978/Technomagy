package zone.moddev.mc.technomagy.machine;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration for mana links (D87-D91): the server file {@code technomagy-machines-server.toml}
 * and the client file {@code technomagy-machines-client.toml}. Every default is the value the
 * design states.
 *
 * <p>
 * Values are read where they are used, never cached, so a reloaded server config applies from the
 * next pulse. Reading a server value before the server config has loaded throws, by NeoForge's
 * design; links only read them from server ticks and player actions, after loading.
 */
public final class MachineConfig {
    /** The server specification, holding the link and transfer rules. */
    public static final ModConfigSpec SERVER_SPEC;

    /** The client specification, holding beam appearance. */
    public static final ModConfigSpec CLIENT_SPEC;

    /** The highest value {@link #MAX_LINK_LENGTH} may be set to, in blocks. */
    public static final int LINK_LENGTH_LIMIT = 64;

    /** Longest link, in blocks between the endpoints' centres (D87). */
    public static final ModConfigSpec.IntValue MAX_LINK_LENGTH;

    /** Game ticks between transfer pulses (D89). */
    public static final ModConfigSpec.IntValue PULSE_INTERVAL;

    /** Packets a node director holds (D89). */
    public static final ModConfigSpec.IntValue DIRECTOR_CAPACITY;

    /** Units in each packet a link draws from a flat handler, Raw counted per D47 (D89). */
    public static final ModConfigSpec.IntValue FLAT_PACKET_UNITS;

    /** Pulses without a transfer after which a link counts as idle (D91). */
    public static final ModConfigSpec.IntValue IDLE_PULSES;

    /** How far a beam's centre line wanders from the straight line, in blocks (D91). */
    public static final ModConfigSpec.DoubleValue WANDER_AMPLITUDE;

    /** Helix turns per block of beam length (D91). */
    public static final ModConfigSpec.DoubleValue HELIX_FREQUENCY;

    /** Travelling motes per block of beam length (D91). */
    public static final ModConfigSpec.DoubleValue MOTE_DENSITY;

    /** Draws plain straight strands with no wander, helix, or motes (D91). */
    public static final ModConfigSpec.BooleanValue REDUCED_EFFECTS;

    static {
        ModConfigSpec.Builder server = new ModConfigSpec.Builder();
        server.comment("Mana links between node directors, prisms and mana handlers (D87-D90)")
            .push("links");
        MAX_LINK_LENGTH = server
            .comment("Longest link, in blocks between the two endpoints' centres (D87)")
            .defineInRange("maxLength", 16, 2, LINK_LENGTH_LIMIT);
        PULSE_INTERVAL = server.comment("Game ticks between transfer pulses (D89)")
            .defineInRange("pulseIntervalTicks", 10, 1, 1200);
        DIRECTOR_CAPACITY = server.comment("Packets a node director holds (D89)")
            .defineInRange("directorCapacity", 8, 1, 64);
        FLAT_PACKET_UNITS = server
            .comment("Units per packet drawn from a plain mana handler; Raw counts per D47 (D89)")
            .defineInRange("flatPacketUnits", 1, 1, 64);
        IDLE_PULSES = server
            .comment("Pulses without a transfer after which a link is drawn as idle (D91)")
            .defineInRange("idlePulses", 2, 1, 100);
        server.pop();
        SERVER_SPEC = server.build();

        ModConfigSpec.Builder client = new ModConfigSpec.Builder();
        client.comment("How mana beams are drawn (D91)").push("beams");
        WANDER_AMPLITUDE = client
            .comment("How far a beam's centre line wanders from straight, in blocks")
            .defineInRange("wanderAmplitude", 0.15, 0.0, 0.5);
        HELIX_FREQUENCY = client.comment("Helix turns per block of beam length")
            .defineInRange("helixFrequency", 1.5, 0.0, 8.0);
        MOTE_DENSITY = client.comment("Travelling motes per block of beam length")
            .defineInRange("moteDensity", 2.0, 0.0, 8.0);
        REDUCED_EFFECTS = client
            .comment("Draw plain straight strands, without wander, helix or motes")
            .define("reducedEffects", false);
        client.pop();
        CLIENT_SPEC = client.build();
    }

    private MachineConfig() {
    }
}
