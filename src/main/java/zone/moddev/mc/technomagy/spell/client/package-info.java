/**
 * Paired elemental orbs display the latest server-owned personal pools (D75).
 *
 * Client-only machinery element selection presentation (D73); Sneak + the selection key toggles the
 * focus's linking mode, and using the focus on a beam in that mode asks to imprint it (D87, D88).
 * Ability Hotbar owns spell selection (D74). Selection is sent as intent; the server owns held-item
 * checks, selected spell and mana type state, targeting, and all world effects. This package must
 * not be initialized by the dedicated server or retain authoritative spell state.
 */
package zone.moddev.mc.technomagy.spell.client;
