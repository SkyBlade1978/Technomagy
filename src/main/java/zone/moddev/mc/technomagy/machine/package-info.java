/**
 * Machine pieces: the blocks that rituals, Technomagy's free-form multiblock machines, are built
 * from (D30, D31).
 *
 * <p>
 * So far these are the {@link zone.moddev.mc.technomagy.machine.NodeDirectorBlock node director},
 * whose platform previews eight compass bearings and stays unlit until powered (D21, D23), the
 * {@link zone.moddev.mc.technomagy.machine.PrismBlock prism}, which splits or combines it (D20,
 * D26), and a creative-only Raw mana source for testing.
 * {@link zone.moddev.mc.technomagy.machine.MachineContent} registers them. They are ordinary
 * blocks, not MMDLib materials. Erosion processing resolves outputs through MMDLib's public
 * crushing recipe type (D61), and Forge resolves live smelting recipes (D51). Shared recipe lookup
 * neither consumes inventory nor changes the world. Director light is rechecked when its chunk
 * loads so old constant-light preview saves acquire the current power-dependent lighting without a
 * per-tick migration scan (D23).
 *
 * <p>
 * This package depends on {@code mana} for the capability. It must not depend on {@code
 * crystal}: machine pieces work with mana, not with crystal blocks directly.
 */
package zone.moddev.mc.technomagy.machine;
