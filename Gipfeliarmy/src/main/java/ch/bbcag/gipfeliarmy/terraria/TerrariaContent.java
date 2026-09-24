package ch.bbcag.gipfeliarmy.terraria;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import ch.bbcag.gipfeliarmy.Config;
import ch.bbcag.gipfeliarmy.GipfeliArmyMod;

// Everything out of Terraria: the Last Prism in both its colours, and three of its bosses with
// the lairs they live in. Registered here rather than beside the Gipfaeli things in GipfeliArmyMod,
// so the whole of it is in one package and the mod class only has to hand it the event bus.
public final class TerrariaContent {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(GipfeliArmyMod.MODID);
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, GipfeliArmyMod.MODID);

    // The Last Prism, and the Random one that is crafted from it and back (see LastPrism).
    public static final DeferredItem<LastPrism> LAST_PRISM = ITEMS.registerItem("last_prism",
            p -> new LastPrism(p, false), p -> p.stacksTo(1).rarity(Rarity.EPIC).fireResistant());
    public static final DeferredItem<LastPrism> LAST_PRISM_RANDOM = ITEMS.registerItem("last_prism_random",
            p -> new LastPrism(p, true), p -> p.stacksTo(1).rarity(Rarity.EPIC).fireResistant());

    // What the bosses' shots look like in flight (see BossBolt). Never in a creative tab.
    public static final DeferredItem<Item> BOSS_LASER = ITEMS.registerSimpleItem("boss_laser");
    public static final DeferredItem<Item> PLANTERA_SEED = ITEMS.registerSimpleItem("plantera_seed");
    public static final DeferredItem<Item> PLANTERA_POISON_SEED = ITEMS.registerSimpleItem("plantera_poison_seed");
    public static final DeferredItem<Item> PLANTERA_THORN_BALL = ITEMS.registerSimpleItem("plantera_thorn_ball");

    public static final DeferredHolder<EntityType<?>, EntityType<EyeOfCthulhu>> EYE_OF_CTHULHU = ENTITY_TYPES.register("eye_of_cthulhu",
            () -> EntityType.Builder.of(EyeOfCthulhu::new, MobCategory.MONSTER)
                    .noLootTable()
                    .fireImmune()
                    .sized(2.6F, 2.6F)
                    .eyeHeight(1.3F)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .build(key("eye_of_cthulhu")));

    public static final DeferredHolder<EntityType<?>, EntityType<WallOfFlesh>> WALL_OF_FLESH = ENTITY_TYPES.register("wall_of_flesh",
            () -> EntityType.Builder.of(WallOfFlesh::new, MobCategory.MONSTER)
                    .noLootTable()
                    .fireImmune()
                    .sized(5.0F, 10.0F)
                    .eyeHeight(7.6F)
                    .clientTrackingRange(16)
                    .build(key("wall_of_flesh")));

    public static final DeferredHolder<EntityType<?>, EntityType<Plantera>> PLANTERA = ENTITY_TYPES.register("plantera",
            () -> EntityType.Builder.of(Plantera::new, MobCategory.MONSTER)
                    .noLootTable()
                    .fireImmune()
                    .sized(2.4F, 2.4F)
                    .eyeHeight(1.2F)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .build(key("plantera")));

    public static final DeferredHolder<EntityType<?>, EntityType<BossBolt>> BOSS_BOLT = ENTITY_TYPES.register("boss_bolt",
            () -> EntityType.Builder.<BossBolt>of(BossBolt::new, MobCategory.MISC)
                    .noLootTable()
                    .sized(0.4F, 0.4F)
                    .clientTrackingRange(8)
                    // Often enough that a homing spore's curve is followed rather than guessed at.
                    .updateInterval(2)
                    .build(key("boss_bolt")));

    private TerrariaContent() {
    }

    private static ResourceKey<EntityType<?>> key(String path) {
        return ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GipfeliArmyMod.MODID, path));
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(TerrariaContent::registerAttributes);
        modEventBus.addListener(TerrariaContent::addCreative);
        modEventBus.addListener(TerrariaNetwork::register);
        NeoForge.EVENT_BUS.register(TerrariaContent.class);
    }

    private static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(EYE_OF_CTHULHU.get(), EyeOfCthulhu.createAttributes().build());
        event.put(WALL_OF_FLESH.get(), WallOfFlesh.createAttributes().build());
        event.put(PLANTERA.get(), Plantera.createAttributes().build());
    }

    private static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT && Config.on(Config.ENABLE_LAST_PRISM)) {
            event.accept(LAST_PRISM);
            event.accept(LAST_PRISM_RANDOM);
        }
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        BossArenas.tick(event.getServer());
    }

    // Every client is told where the lairs are when it joins, so the waypoints are there from the
    // first frame rather than from the next time one changes.
    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BossArenas.get(player.level().getServer()).send(player);
        }
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        TerrariaCommand.register(event.getDispatcher());
    }
}
