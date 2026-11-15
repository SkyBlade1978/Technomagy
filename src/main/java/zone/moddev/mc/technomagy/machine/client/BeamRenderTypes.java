package zone.moddev.mc.technomagy.machine.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * The render type mana beams are drawn with (D91): untextured position-colour quads, blended
 * additively like lightning so overlapping strands glow, unaffected by world light, drawn from both
 * sides, and not writing depth so a beam never hides the beam behind it.
 *
 * <p>
 * Stateless; the render type is created once when this class loads on the render thread.
 */
final class BeamRenderTypes {
    /** The beam render type. */
    static final RenderType BEAM = RenderType.create(
        "technomagy_mana_beam",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        1 << 16,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_LIGHTNING_SHADER)
            .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(RenderStateShard.TRANSLUCENT_TARGET)
            .createCompositeState(false));

    private BeamRenderTypes() {
    }
}
