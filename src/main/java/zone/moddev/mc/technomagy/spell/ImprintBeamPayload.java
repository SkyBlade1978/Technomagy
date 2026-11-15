package zone.moddev.mc.technomagy.spell;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import net.neoforged.neoforge.network.handling.IPayloadContext;

import zone.moddev.mc.technomagy.machine.FitLensPayload.BeamRef;

/**
 * A player's use of a focus in linking mode on a beam, asking to imprint that beam's construction
 * as the focus's filter (D88). The client only names the beam; the server checks the held focus,
 * its linking mode, that the link exists and is in reach, and that it has carried a packet.
 *
 * @param beam
 *            the picked beam
 * @param offhand
 *            whether the focus is in the offhand
 */
public record ImprintBeamPayload(BeamRef beam, boolean offhand) implements CustomPacketPayload {
    /** The payload's channel identity. */
    public static final Type<ImprintBeamPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("technomagy", "imprint_beam"));

    /** Sends the beam reference and the hand. */
    public static final StreamCodec<ByteBuf, ImprintBeamPayload> CODEC = StreamCodec.composite(
        BeamRef.STREAM_CODEC, ImprintBeamPayload::beam,
        ByteBufCodecs.BOOL, ImprintBeamPayload::offhand,
        ImprintBeamPayload::new);

    /** Identifies this payload to NeoForge's channel registry. */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Validates and imprints on the server thread.
     *
     * @param payload
     *            the request
     * @param context
     *            the network context
     */
    static void handle(ImprintBeamPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            var stack = player.getItemInHand(
                payload.offhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            if (!stack.is(SpellContent.FOCUS.get()) || !FocusLinking.linking(stack)) {
                return;
            }
            payload.beam().resolve(player.serverLevel(), player).ifPresentOrElse(
                link -> FocusLinking.imprint(player, stack, link),
                () -> player.displayClientMessage(
                    Component.translatable("message.technomagy.lens.no_beam"), true));
        });
    }
}
