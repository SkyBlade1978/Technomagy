package zone.moddev.mc.technomagy.spell;

import java.util.Optional;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import org.slf4j.Logger;

import zone.moddev.mc.technomagy.machine.LinkFilter;
import zone.moddev.mc.technomagy.machine.ManaLink;
import zone.moddev.mc.technomagy.machine.ManaLinks;
import zone.moddev.mc.technomagy.mana.ManaType;

/**
 * The focus as the link wand (D73, D87, D88): its linking mode, the link it is in the middle of
 * making, and the filter it gives new links. All of it is saved on the focus item beside its
 * element selection, and changed only on the server after checking the held item.
 *
 * <p>
 * <b>The filter</b> is the focus's selected element or Raw, unless a compound filter has been
 * imprinted from a beam or taken from the hotbar spell; choosing an element in the selector clears
 * a compound filter again (D88).
 *
 * <p>
 * <b>Linking.</b> In linking mode, using the focus on a block starts a link there; using it on a
 * second block makes or removes the link from the first to the second ({@link ManaLinks#toggle});
 * using it on the first block again cancels. A failed attempt keeps the start, so the player can
 * try another target. Leaving linking mode, or changing dimension, forgets the start. A start that
 * is loaded but no longer a link end (the block was broken or replaced) is forgotten at the next
 * use, and the block used becomes the new start (D87).
 *
 * <p>
 * Stateless; every method runs on the server thread.
 */
public final class FocusLinking {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LINKING = "technomagy_linking";
    private static final String FROM = "technomagy_link_from";
    private static final String FROM_DIMENSION = "technomagy_link_dimension";
    private static final String FILTER = "technomagy_filter";

    private FocusLinking() {
    }

    /**
     * Whether a focus is in linking mode. Public so the client can decide whether to pick a beam;
     * the server always re-checks.
     *
     * @param stack
     *            the focus
     * @return true in linking mode; false for a focus that has never toggled it
     */
    public static boolean linking(ItemStack stack) {
        return tag(stack).getBoolean(LINKING);
    }

    /**
     * Switches linking mode on the held focus and tells the player the new mode (D87). Leaving
     * linking mode forgets any half-made link.
     *
     * @param player
     *            the player holding it
     * @param stack
     *            the focus
     */
    static void toggle(ServerPlayer player, ItemStack stack) {
        boolean linking = !linking(stack);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putBoolean(LINKING, linking);
            if (!linking) {
                tag.remove(FROM);
                tag.remove(FROM_DIMENSION);
            }
        });
        player.displayClientMessage(Component.translatable(linking
            ? "message.technomagy.linking.on"
            : "message.technomagy.linking.off", describe(filter(stack))), true);
    }

    /**
     * The filter a new link would get (D88): the compound filter if one is set, otherwise the
     * selected element or Raw.
     *
     * @param stack
     *            the focus
     * @return the filter, or empty when nothing is selected
     */
    static Optional<LinkFilter> filter(ItemStack stack) {
        CompoundTag tag = tag(stack);
        if (tag.contains(FILTER)) {
            Optional<LinkFilter> compound = LinkFilter.CODEC.parse(NbtOps.INSTANCE, tag.get(FILTER))
                .resultOrPartial(error -> LOGGER.error(
                    "Unreadable link filter on a focus: {}. Clearing it.", error));
            if (compound.isPresent()) {
                return compound;
            }
            CustomData.update(DataComponents.CUSTOM_DATA, stack, saved -> saved.remove(FILTER));
        }
        return SpellFocusItem.selectedElement(stack).map(LinkFilter::pure);
    }

    /**
     * Sets a compound filter, from an imprinted beam or the hotbar spell (D88).
     *
     * @param stack
     *            the focus
     * @param filter
     *            the filter
     */
    static void setFilter(ItemStack stack, LinkFilter filter) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(FILTER,
            LinkFilter.CODEC.encodeStart(NbtOps.INSTANCE, filter).getOrThrow()));
    }

    /**
     * Clears a compound filter, so the element selection applies again (D88).
     *
     * @param stack
     *            the focus
     */
    static void clearFilter(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(FILTER));
    }

    /**
     * Handles using a focus in linking mode on a block, as the class describes.
     *
     * @param player
     *            the player
     * @param stack
     *            the focus
     * @param pos
     *            the block used on
     */
    static void useOn(ServerPlayer player, ItemStack stack, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        String dimension = level.dimension().location().toString();
        CompoundTag tag = tag(stack);
        Optional<BlockPos> from = tag.contains(FROM)
            && dimension.equals(tag.getString(FROM_DIMENSION))
                ? Optional.of(BlockPos.of(tag.getLong(FROM)))
                : Optional.empty();
        // A start whose block was broken or replaced can never make a link; drop it and let this
        // use start afresh. A start in an unloaded chunk is kept: toggle reports it as unloaded.
        boolean restarted = from.isPresent() && level.isLoaded(from.get())
            && !ManaLinks.isEndpoint(level, from.get());
        if (restarted) {
            forgetStart(stack);
            from = Optional.empty();
        }
        if (from.isEmpty()) {
            if (!ManaLinks.isEndpoint(level, pos)) {
                player.displayClientMessage(restarted
                    ? Component.translatable("message.technomagy.linking.start_lost")
                    : ManaLinks.Result.NOT_ENDPOINT.message(), true);
                return;
            }
            CustomData.update(DataComponents.CUSTOM_DATA, stack, saved -> {
                saved.putLong(FROM, pos.asLong());
                saved.putString(FROM_DIMENSION, dimension);
            });
            player.displayClientMessage(Component.translatable(restarted
                ? "message.technomagy.linking.restarted"
                : "message.technomagy.linking.from",
                level.getBlockState(pos).getBlock().getName(), describe(filter(stack))), true);
            return;
        }
        if (from.get().equals(pos)) {
            forgetStart(stack);
            player.displayClientMessage(
                Component.translatable("message.technomagy.linking.cancelled"), true);
            return;
        }
        ManaLinks.Result result = ManaLinks.toggle(level, from.get(), pos, filter(stack));
        if (result == ManaLinks.Result.CREATED || result == ManaLinks.Result.REMOVED) {
            forgetStart(stack);
        }
        player.displayClientMessage(result.message(), true);
    }

    /**
     * Takes the hotbar's selected spell as the compound filter (D88): Sneak + use at the air in
     * linking mode.
     *
     * @param player
     *            the player
     * @param stack
     *            the focus
     */
    static void takeHotbarSpell(ServerPlayer player, ItemStack stack) {
        Optional<PlayerSpell> spell = SpellContent.selectedSpell(player);
        if (spell.isEmpty()) {
            player.displayClientMessage(
                Component.translatable("message.technomagy.select_spell"), true);
            return;
        }
        LinkFilter filter = LinkFilter.of(spell.get().stream());
        setFilter(stack, filter);
        player.displayClientMessage(Component.translatable("message.technomagy.filter.spell",
            spell.get().title(), filter.describe()), true);
    }

    /**
     * Copies a flowing beam's construction onto the focus as its filter (D88).
     *
     * @param player
     *            the player
     * @param stack
     *            the focus
     * @param link
     *            the picked link, already checked to exist and be in reach
     */
    static void imprint(ServerPlayer player, ItemStack stack, ManaLink link) {
        Optional<LinkFilter> filter = link.lastPacket()
            .map(packet -> new LinkFilter(packet.shape()));
        if (filter.isEmpty()) {
            player.displayClientMessage(
                Component.translatable("message.technomagy.filter.idle_beam"), true);
            return;
        }
        if (filter.get().pureType().isPresent()) {
            // A pure stream is a pure filter; keep the selector authoritative for those (D88).
            ManaType type = filter.get().pureType().get();
            SpellFocusItem.selectElement(stack, type);
            clearFilter(stack);
        } else {
            setFilter(stack, filter.get());
        }
        player.displayClientMessage(Component.translatable("message.technomagy.filter.imprinted",
            filter.get().describe()), true);
    }

    /** Forgets the half-made link. */
    private static void forgetStart(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, saved -> {
            saved.remove(FROM);
            saved.remove(FROM_DIMENSION);
        });
    }

    /** The filter for a message, or "none". */
    private static Component describe(Optional<LinkFilter> filter) {
        return filter.map(LinkFilter::describe)
            .orElseGet(() -> Component.translatable("message.technomagy.filter.none"));
    }

    /** A copy of the focus's custom data. */
    private static CompoundTag tag(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }
}
