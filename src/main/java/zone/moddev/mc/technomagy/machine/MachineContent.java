package zone.moddev.mc.technomagy.machine;

import java.util.List;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import zone.moddev.mc.technomagy.Technomagy;
import zone.moddev.mc.technomagy.mana.IManaHandler;
import zone.moddev.mc.technomagy.mana.InfiniteManaSource;
import zone.moddev.mc.technomagy.mana.ManaCapabilities;
import zone.moddev.mc.technomagy.mana.ManaSink;
import zone.moddev.mc.technomagy.mana.ManaType;

/**
 * Registers the machine pieces that rituals are built from (D30), their items and creative tab
 * entries, their mana capabilities, and everything mana links need (D87-D92): the director and
 * prism block entities, the three link lenses, the lens payload, and the machine configs.
 *
 * <p>
 * Unlike the crystals and stones, machine pieces are ordinary blocks, not MMDLib materials: there
 * is one of each, with no per-flavor variants.
 *
 * <p>
 * Not thread-safe: {@link #register} runs once on the mod-loading thread.
 */
public final class MachineContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister
        .createBlocks(Technomagy.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister
        .createItems(Technomagy.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister
        .create(Registries.BLOCK_ENTITY_TYPE, Technomagy.MODID);

    /** The node director, which directs mana streams (D21). */
    public static final DeferredBlock<NodeDirectorBlock> NODE_DIRECTOR = BLOCKS.register(
        "node_director",
        () -> new NodeDirectorBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GRAY)
                .strength(1.5f, 6.0f)
                .requiresCorrectToolForDrops()
                .sound(SoundType.STONE)));

    /** The node director's item. */
    public static final DeferredItem<BlockItem> NODE_DIRECTOR_ITEM = ITEMS
        .registerSimpleBlockItem(NODE_DIRECTOR);

    /** The prism, which splits or combines mana (D20). */
    public static final DeferredBlock<PrismBlock> PRISM = BLOCKS.register(
        "prism",
        () -> new PrismBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GRAY)
                .strength(1.5f, 6.0f)
                .requiresCorrectToolForDrops()
                .sound(SoundType.STONE)));

    /** The prism's item. */
    public static final DeferredItem<BlockItem> PRISM_ITEM = ITEMS.registerSimpleBlockItem(PRISM);

    /** The node director's block entity: its links and packet queue (D89). */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NodeDirectorBlockEntity>> NODE_DIRECTOR_ENTITY = BLOCK_ENTITY_TYPES
        .register("node_director", () -> BlockEntityType.Builder
            .of(NodeDirectorBlockEntity::new, NODE_DIRECTOR.get()).build(null));

    /** The prism's block entity: its links, ports and slots (D89). */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PrismBlockEntity>> PRISM_ENTITY = BLOCK_ENTITY_TYPES
        .register("prism", () -> BlockEntityType.Builder
            .of(PrismBlockEntity::new, PRISM.get()).build(null));

    /** The tier-1 rate lens: 2 packets per pulse (D90). */
    public static final DeferredItem<LinkLensItem> LENS_MINOR = ITEMS.register(
        "link_lens_minor", () -> new LinkLensItem(new Item.Properties(), 1));

    /** The tier-2 rate lens: 4 packets per pulse (D90). */
    public static final DeferredItem<LinkLensItem> LENS_GREATER = ITEMS.register(
        "link_lens_greater", () -> new LinkLensItem(new Item.Properties(), 2));

    /** The tier-3 rate lens: 8 packets per pulse (D90). */
    public static final DeferredItem<LinkLensItem> LENS_GRAND = ITEMS.register(
        "link_lens_grand", () -> new LinkLensItem(new Item.Properties(), 3));

    private static final List<DeferredItem<LinkLensItem>> LENSES = List.of(
        LENS_MINOR, LENS_GREATER, LENS_GRAND);

    /**
     * An endless supply of Raw mana, for testing mana links. It has no recipe, so it is only
     * available in creative mode. It always renders fully lit and gives off light level 15.
     */
    public static final DeferredBlock<Block> RAW_MANA_SOURCE = BLOCKS.registerSimpleBlock(
        "raw_mana_source",
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_LIGHT_BLUE)
            .strength(1.5f, 6.0f)
            .sound(SoundType.AMETHYST)
            .lightLevel(state -> 15)
            .emissiveRendering((state, level, pos) -> true));

    /** The raw mana source's item. */
    public static final DeferredItem<BlockItem> RAW_MANA_SOURCE_ITEM = ITEMS
        .registerSimpleBlockItem(RAW_MANA_SOURCE);

    /** The raw mana source's handler. Stateless, so one instance serves every source block. */
    private static final IManaHandler RAW_SUPPLY = new InfiniteManaSource(ManaType.RAW);

    /**
     * A bottomless drain for mana links, for testing: it accepts every packet and destroys it, so a
     * link into it never fills and its beam stays active (D91). It has no recipe, so it is only
     * available in creative mode.
     */
    public static final DeferredBlock<Block> MANA_SINK = BLOCKS.registerSimpleBlock(
        "mana_sink",
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_BLACK)
            .strength(1.5f, 6.0f)
            .sound(SoundType.AMETHYST));

    /** The mana sink's item. */
    public static final DeferredItem<BlockItem> MANA_SINK_ITEM = ITEMS
        .registerSimpleBlockItem(MANA_SINK);

    /** The mana sink's handler. Stateless, so one instance serves every sink block. */
    private static final IManaHandler SINK = new ManaSink();

    private MachineContent() {
    }

    /**
     * The lens that brings a link to a tier (D90).
     *
     * @param tier
     *            1 to {@value ManaLink#MAX_TIER}
     * @return the lens item
     * @throws IndexOutOfBoundsException
     *             if the tier is out of range
     */
    public static Item lens(int tier) {
        return LENSES.get(tier - 1).get();
    }

    /**
     * Registers the blocks, block entities, items, creative tab entries, capabilities, payloads,
     * and the machine configs. Called once from the mod constructor.
     *
     * @param modBus
     *            the mod event bus
     * @param container
     *            the mod container, which owns the configs
     */
    public static void register(IEventBus modBus, ModContainer container) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
        modBus.addListener(MachineContent::addToTabs);
        modBus.addListener(MachineContent::registerCapabilities);
        modBus.addListener(MachineContent::registerPayloads);
        container.registerConfig(ModConfig.Type.SERVER, MachineConfig.SERVER_SPEC,
            "technomagy-machines-server.toml");
        container.registerConfig(ModConfig.Type.CLIENT, MachineConfig.CLIENT_SPEC,
            "technomagy-machines-client.toml");
    }

    /** Registers the lens request (D90); the client only names a beam, the server decides. */
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(Technomagy.MODID)
            .versioned("1")
            .playToServer(FitLensPayload.TYPE, FitLensPayload.CODEC, FitLensPayload::handle);
    }

    /**
     * Gives the raw mana source and the mana sink their mana capabilities (D24), which need no
     * block entity, and the director and prism theirs, served by their block entities.
     */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlock(
            ManaCapabilities.BLOCK,
            (level, pos, state, blockEntity, side) -> RAW_SUPPLY,
            RAW_MANA_SOURCE.get());
        event.registerBlock(
            ManaCapabilities.BLOCK,
            (level, pos, state, blockEntity, side) -> SINK,
            MANA_SINK.get());
        event.registerBlockEntity(ManaCapabilities.BLOCK, NODE_DIRECTOR_ENTITY.get(),
            (node, side) -> node.handler());
        event.registerBlockEntity(ManaCapabilities.BLOCK, PRISM_ENTITY.get(),
            (node, side) -> node.handler());
    }

    /** Lists the machine pieces in the vanilla Functional Blocks tab. */
    private static void addToTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(NODE_DIRECTOR_ITEM);
            event.accept(PRISM_ITEM);
            event.accept(RAW_MANA_SOURCE_ITEM);
            event.accept(MANA_SINK_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            LENSES.forEach(event::accept);
        }
    }
}
