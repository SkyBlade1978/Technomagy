package zone.moddev.mc.technomagy.spell;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * A player's Sneak + element key press, asking to switch the held focus's linking mode (D87). The
 * server checks the hand actually holds a focus before changing it.
 *
 * @param offhand
 *            whether the focus is in the offhand
 */
public record ToggleLinkingPayload(boolean offhand) implements CustomPacketPayload {
    /** The payload's channel identity. */
    public static final Type<ToggleLinkingPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("technomagy", "toggle_linking"));

    /** Sends only the hand. */
    public static final StreamCodec<ByteBuf, ToggleLinkingPayload> CODEC = ByteBufCodecs.BOOL
        .map(ToggleLinkingPayload::new, ToggleLinkingPayload::offhand);

    /** Identifies this payload to NeoForge's channel registry. */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Toggles the mode on the server thread, if the hand holds a focus.
     *
     * @param payload
     *            the request
     * @param context
     *            the network context
     */
    static void handle(ToggleLinkingPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            var stack = player.getItemInHand(
                payload.offhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            if (stack.is(SpellContent.FOCUS.get())) {
                FocusLinking.toggle(player, stack);
            }
        });
    }
}
