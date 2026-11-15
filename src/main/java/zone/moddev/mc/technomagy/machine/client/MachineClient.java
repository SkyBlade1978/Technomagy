package zone.moddev.mc.technomagy.machine.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import zone.moddev.mc.technomagy.machine.FitLensPayload;
import zone.moddev.mc.technomagy.machine.LinkLensItem;
import zone.moddev.mc.technomagy.machine.MachineContent;

/**
 * Client wiring for mana links: the beam renderer for both node types (D91), the lenses' tints, and
 * fitting a held lens to the beam under the crosshair (D90).
 *
 * <p>
 * Loaded only on the client; the subscriber is registered for {@link Dist#CLIENT} only.
 */
@EventBusSubscriber(modid = "technomagy", value = Dist.CLIENT)
public final class MachineClient {
    /** Placeholder lens tints until textures exist (D90): pale blue, violet, gold. */
    private static final int[] LENS_TINTS = {0xffb0e0ff, 0xffc890ff, 0xffffd070};

    private MachineClient() {
    }

    /**
     * Draws beams from both node types.
     *
     * @param event
     *            the renderer registration event
     */
    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(MachineContent.NODE_DIRECTOR_ENTITY.get(),
            ManaBeamRenderer::new);
        event.registerBlockEntityRenderer(MachineContent.PRISM_ENTITY.get(),
            ManaBeamRenderer::new);
    }

    /**
     * Tints the placeholder lens models by tier.
     *
     * @param event
     *            the item colour registration event
     */
    @SubscribeEvent
    public static void lensTints(RegisterColorHandlersEvent.Item event) {
        event.register((stack, layer) -> stack.getItem() instanceof LinkLensItem lens
            ? LENS_TINTS[lens.tier() - 1]
            : 0xffffffff,
            MachineContent.LENS_MINOR.get(), MachineContent.LENS_GREATER.get(),
            MachineContent.LENS_GRAND.get());
    }

    /**
     * Using a lens while looking at a beam asks the server to fit it (D90), instead of the item's
     * ordinary use. Without a beam under the crosshair the use proceeds as normal.
     *
     * @param event
     *            the use key event
     */
    @SubscribeEvent
    public static void useLens(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        ItemStack stack = minecraft.player.getItemInHand(event.getHand());
        if (!(stack.getItem() instanceof LinkLensItem)) {
            return;
        }
        BeamPicker.pick(minecraft).ifPresent(beam -> {
            event.setCanceled(true);
            event.setSwingHand(true);
            PacketDistributor.sendToServer(
                new FitLensPayload(beam, event.getHand() == InteractionHand.OFF_HAND));
        });
    }
}
