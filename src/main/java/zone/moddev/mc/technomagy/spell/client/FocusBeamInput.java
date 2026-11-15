package zone.moddev.mc.technomagy.spell.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import zone.moddev.mc.technomagy.machine.client.BeamPicker;
import zone.moddev.mc.technomagy.spell.FocusLinking;
import zone.moddev.mc.technomagy.spell.ImprintBeamPayload;
import zone.moddev.mc.technomagy.spell.SpellContent;

/**
 * Using a focus in linking mode while looking at a beam asks the server to imprint that beam's
 * construction as the focus's filter (D88). Without a beam under the crosshair, or with Sneak held
 * (which takes the hotbar spell instead), the use proceeds as normal.
 *
 * <p>
 * Client only. The client reads the focus's linking mode from its synced item data just to decide
 * whether to pick; the server checks it again.
 */
@EventBusSubscriber(modid = "technomagy", value = Dist.CLIENT)
public final class FocusBeamInput {
    private FocusBeamInput() {
    }

    /**
     * Picks a beam on use and sends the imprint request.
     *
     * @param event
     *            the use key event
     */
    @SubscribeEvent
    public static void use(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.isShiftKeyDown()) {
            return;
        }
        ItemStack stack = minecraft.player.getItemInHand(event.getHand());
        if (!stack.is(SpellContent.FOCUS.get()) || !FocusLinking.linking(stack)) {
            return;
        }
        BeamPicker.pick(minecraft).ifPresent(beam -> {
            event.setCanceled(true);
            event.setSwingHand(true);
            PacketDistributor.sendToServer(
                new ImprintBeamPayload(beam, event.getHand() == InteractionHand.OFF_HAND));
        });
    }
}
