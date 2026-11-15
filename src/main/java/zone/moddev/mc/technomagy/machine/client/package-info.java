/**
 * Client-only drawing and picking of mana links (D90, D91): the procedural beam renderer, the
 * anchors where beams meet blocks, the beam picker used for imprinting filters and fitting lenses,
 * and the client registrations (renderers, lens tints, lens use).
 *
 * <p>
 * Everything here reads only the {@link zone.moddev.mc.technomagy.machine.BeamState}s a node's
 * update tag carries; it never holds links, packets, or other server state, and a pick only names a
 * beam for the server to re-validate. This package must not be loaded on a dedicated server. It may
 * depend on {@code machine} and {@code mana}, but not on {@code spell}.
 */
package zone.moddev.mc.technomagy.machine.client;
