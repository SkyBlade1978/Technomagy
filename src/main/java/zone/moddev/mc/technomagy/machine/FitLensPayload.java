package zone.moddev.mc.technomagy.machine;

import java.util.Optional;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * A player's request to fit the held lens to the beam they are looking at (D90). The client only
 * names the beam; the server checks the held item, that the link exists, that the player is within
 * reach of it, and that the link is one tier below the lens, before consuming the lens.
 *
 * @param beam
 *            the picked beam
 * @param offhand
 *            whether the lens is in the offhand
 */
public record FitLensPayload(BeamRef beam, boolean offhand) implements CustomPacketPayload {
    /** The payload's channel identity. */
    public static final Type<FitLensPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("technomagy", "fit_lens"));

    /** Sends the beam reference and the hand. */
    public static final StreamCodec<ByteBuf, FitLensPayload> CODEC = StreamCodec.composite(
        BeamRef.STREAM_CODEC, FitLensPayload::beam,
        ByteBufCodecs.BOOL, FitLensPayload::offhand,
        FitLensPayload::new);

    /** Identifies this payload to NeoForge's channel registry. */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Validates and applies the request on the server thread, telling the player the outcome.
     *
     * @param payload
     *            the request
     * @param context
     *            the network context
     */
    static void handle(FitLensPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            InteractionHand hand = payload.offhand()
                ? InteractionHand.OFF_HAND
                : InteractionHand.MAIN_HAND;
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof LinkLensItem lens)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            Optional<ManaLink> link = payload.beam().resolve(level, player);
            if (link.isEmpty()) {
                player.displayClientMessage(
                    Component.translatable("message.technomagy.lens.no_beam"), true);
                return;
            }
            if (link.get().tier() != lens.tier() - 1) {
                player.displayClientMessage(Component.translatable(
                    link.get().tier() >= lens.tier()
                        ? "message.technomagy.lens.already_fitted"
                        : "message.technomagy.lens.needs_lower",
                    link.get().tier()), true);
                return;
            }
            link.get().setTier(lens.tier());
            if (level.getBlockEntity(payload.beam().owner()) instanceof ManaNodeBlockEntity node) {
                node.tierChanged();
            }
            stack.consume(1, player);
            player.displayClientMessage(Component.translatable(
                "message.technomagy.lens.fitted", link.get().rate()), true);
        });
    }

    /**
     * Names one link for a client-to-server request: the node that owns it and its two ends.
     *
     * @param owner
     *            the owning node
     * @param from
     *            the link's source
     * @param to
     *            the link's target
     */
    public record BeamRef(BlockPos owner, BlockPos from, BlockPos to) {
        /** Sends the three positions. */
        public static final StreamCodec<ByteBuf, BeamRef> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BeamRef::owner,
            BlockPos.STREAM_CODEC, BeamRef::from,
            BlockPos.STREAM_CODEC, BeamRef::to,
            BeamRef::new);

        /**
         * Finds the named link, if it exists and the player can reach it: the closest point of the
         * straight beam must lie within the player's block reach, plus one block for the beam's
         * wander and the client's view of it.
         *
         * @param level
         *            the player's level
         * @param player
         *            the requesting player
         * @return the link, or empty if it does not exist or is out of reach
         */
        public Optional<ManaLink> resolve(ServerLevel level, ServerPlayer player) {
            Optional<ManaLink> link = ManaLinks.find(level, owner, from, to);
            if (link.isEmpty()) {
                return link;
            }
            Vec3 eye = player.getEyePosition();
            Vec3 start = Vec3.atCenterOf(from);
            Vec3 end = Vec3.atCenterOf(to);
            Vec3 along = end.subtract(start);
            double t = Math.clamp(eye.subtract(start).dot(along) / along.lengthSqr(), 0.0, 1.0);
            double distance = start.add(along.scale(t)).distanceTo(eye);
            return distance <= player.blockInteractionRange() + 1.0 ? link : Optional.empty();
        }
    }
}
