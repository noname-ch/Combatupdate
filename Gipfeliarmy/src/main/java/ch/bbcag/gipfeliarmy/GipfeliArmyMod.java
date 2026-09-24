package ch.bbcag.gipfeliarmy;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import ch.bbcag.gipfeliarmy.entity.GipfaeliBomb;
import ch.bbcag.gipfeliarmy.entity.GipfaeliBullet;
import ch.bbcag.gipfeliarmy.entity.GipfaeliGrenade;
import ch.bbcag.gipfeliarmy.entity.GipfaeliRocket;
import ch.bbcag.gipfeliarmy.entity.GipfaeliSoldier;
import ch.bbcag.gipfeliarmy.entity.GipfaeliTnt;
import ch.bbcag.gipfeliarmy.terraria.TerrariaContent;
import ch.bbcag.gipfeliarmy.territory.TerritoryBorders;
import ch.bbcag.gipfeliarmy.territory.TerritoryCommands;
import ch.bbcag.gipfeliarmy.territory.TerritoryManager;
import ch.bbcag.gipfeliarmy.territory.TerritoryNetwork;

// The value here should match an entry in the META-INF/neoforge.mods.toml file.
//
// The Gipfaeli Army mod: territory, the Gipfaeli arsenal (launcher, launch rig, grenade, TNT) and
// the army itself. It grew up inside the combat mod (../combatupdate) and loads beside it, but
// shares no code with it: everything here stands on NeoForge alone.
@Mod(GipfeliArmyMod.MODID)
public final class GipfeliArmyMod {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "gipfeliarmy";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // Everything registered below used to be the combat mod's, under its namespace, and a world
    // saved before the split still names it that way in its chunks, item stacks and entities. Each
    // old name is aliased to the new one (see the constructor), so those lookups resolve; the two
    // saved-data files are re-read under their old names (see TerritoryData and GipfaeliArmyData);
    // and the old config file's values are carried over (see LegacyConfig).
    public static final String LEGACY_NAMESPACE = "combatupdate";
    // Create a Deferred Register to hold Blocks which will all be registered under the "gipfeliarmy" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "gipfeliarmy" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold Entity Types which will all be registered under the "gipfeliarmy" namespace
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    // And one for block entities, of which the guard post is so far the only one
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    // The pastry the launcher throws; see GipfaeliRocket for what it does on arrival
    public static final DeferredHolder<EntityType<?>, EntityType<GipfaeliRocket>> GIPFAELI_ROCKET = ENTITY_TYPES.register("gipfaeli_rocket",
            () -> EntityType.Builder.<GipfaeliRocket>of(GipfaeliRocket::new, MobCategory.MISC)
                    .noLootTable()
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(8)
                    // Tight, because this one can turn: the client has to be told where it actually
                    // went rather than left extrapolating a straight line.
                    .updateInterval(2)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MODID, "gipfaeli_rocket"))));

    // The bomb the launch rig sets down; see GipfaeliBomb for the countdown and the arc it leaves on
    public static final DeferredHolder<EntityType<?>, EntityType<GipfaeliBomb>> GIPFAELI_BOMB = ENTITY_TYPES.register("gipfaeli_bomb",
            () -> EntityType.Builder.<GipfaeliBomb>of(GipfaeliBomb::new, MobCategory.MISC)
                    .noLootTable()
                    .sized(0.5F, 0.5F)
                    // Wider than the rocket's, because a strike is watched from the rig it left rather
                    // than followed: the whole arc has to stay drawn from where the shooter is standing.
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MODID, "gipfaeli_bomb"))));

    // What a Gipfaeli gun throws; see GipfaeliBullet for the one it has in common with all of them
    public static final DeferredHolder<EntityType<?>, EntityType<GipfaeliBullet>> GIPFAELI_BULLET = ENTITY_TYPES.register("gipfaeli_bullet",
            () -> EntityType.Builder.<GipfaeliBullet>of(GipfaeliBullet::new, MobCategory.MISC)
                    .noLootTable()
                    .sized(0.25F, 0.25F)
                    .clientTrackingRange(8)
                    // Looser than the rocket's 2: a round flies dead straight, so the client can
                    // extrapolate the whole of it from where it was last told the thing was.
                    .updateInterval(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MODID, "gipfaeli_bullet"))));

    // The hand grenade in the air; see GipfaeliGrenade for the bounce and the fuse
    public static final DeferredHolder<EntityType<?>, EntityType<GipfaeliGrenade>> GIPFAELI_GRENADE_ENTITY = ENTITY_TYPES.register("gipfaeli_grenade",
            () -> EntityType.Builder.<GipfaeliGrenade>of(GipfaeliGrenade::new, MobCategory.MISC)
                    .noLootTable()
                    .sized(0.25F, 0.25F)
                    .clientTrackingRange(8)
                    // Tight, like the rocket's: a grenade changes direction every time it bounces,
                    // and the client has to be told about each one rather than left to guess.
                    .updateInterval(2)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MODID, "gipfaeli_grenade"))));

    // Lit Gipfaeli TNT of either kind, built the way vanilla builds primed TNT; see GipfaeliTnt
    public static final DeferredHolder<EntityType<?>, EntityType<GipfaeliTnt>> GIPFAELI_TNT = ENTITY_TYPES.register("gipfaeli_tnt",
            () -> EntityType.Builder.<GipfaeliTnt>of(GipfaeliTnt::new, MobCategory.MISC)
                    .noLootTable()
                    .fireImmune()
                    .sized(0.98F, 0.98F)
                    .eyeHeight(0.15F)
                    .clientTrackingRange(10)
                    .updateInterval(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MODID, "gipfaeli_tnt"))));

    // The army itself; see GipfaeliSoldier for what one does with the gun it is handed
    public static final DeferredHolder<EntityType<?>, EntityType<GipfaeliSoldier>> GIPFAELI_SOLDIER = ENTITY_TYPES.register("gipfaeli_soldier",
            () -> EntityType.Builder.of(GipfaeliSoldier::new, MobCategory.CREATURE)
                    // A soldier carries its gun out of the world with it and drops that by hand
                    // (see GipfaeliSoldier#dropCustomDeathLoot); there is nothing else on it to roll for.
                    .noLootTable()
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MODID, "gipfaeli_soldier"))));

    // Gipfaeli TNT, plain and Ultra, with vanilla TNT's own block properties; see GipfaeliTntBlock.
    public static final DeferredBlock<GipfaeliTntBlock> GIPFAELI_TNT_BLOCK = BLOCKS.registerBlock("gipfaeli_tnt",
            p -> new GipfaeliTntBlock(GipfaeliTntBlock.Kind.STANDARD, p),
            p -> p.mapColor(MapColor.COLOR_ORANGE).instabreak().sound(SoundType.GRASS).ignitedByLava().isRedstoneConductor((state, level, pos) -> false));
    public static final DeferredBlock<GipfaeliTntBlock> GIPFAELI_ULTRA_TNT_BLOCK = BLOCKS.registerBlock("gipfaeli_ultra_tnt",
            p -> new GipfaeliTntBlock(GipfaeliTntBlock.Kind.ULTRA, p),
            p -> p.mapColor(MapColor.FIRE).instabreak().sound(SoundType.GRASS).ignitedByLava().isRedstoneConductor((state, level, pos) -> false));
    // The army's guard post: set it down, click it, station soldiers at it; see GipfaeliGuardPost.
    public static final DeferredBlock<GipfaeliGuardPost.PostBlock> GIPFAELI_GUARD_POST = BLOCKS.registerBlock("gipfaeli_guard_post",
            GipfaeliGuardPost.PostBlock::new,
            p -> p.mapColor(MapColor.WOOD).strength(2.0F, 6.0F).sound(SoundType.WOOD));
    public static final DeferredItem<BlockItem> GIPFAELI_GUARD_POST_ITEM = ITEMS.registerSimpleBlockItem("gipfaeli_guard_post", GIPFAELI_GUARD_POST);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GipfaeliGuardPost.Post>> GIPFAELI_GUARD_POST_ENTITY =
            BLOCK_ENTITY_TYPES.register("gipfaeli_guard_post",
                    () -> new BlockEntityType<>(GipfaeliGuardPost.Post::new, GIPFAELI_GUARD_POST.get()));

    public static final DeferredItem<BlockItem> GIPFAELI_TNT_ITEM = ITEMS.registerSimpleBlockItem("gipfaeli_tnt", GIPFAELI_TNT_BLOCK);
    public static final DeferredItem<BlockItem> GIPFAELI_ULTRA_TNT_ITEM = ITEMS.registerSimpleBlockItem("gipfaeli_ultra_tnt", GIPFAELI_ULTRA_TNT_BLOCK);

    // The launcher's ammunition, and a decent breakfast in its own right.
    public static final DeferredItem<Item> GIPFAELI = ITEMS.registerSimpleItem("gipfaeli",
            p -> p.food(new FoodProperties.Builder().nutrition(5).saturationModifier(0.6F).build()));

    // Fires the above; see GipfaeliLauncher for the sight and the trigger.
    public static final DeferredItem<Item> GIPFAELI_LAUNCHER = ITEMS.registerSimpleItem("gipfaeli_launcher",
            p -> p.stacksTo(1));

    // Sets one of the above down as a bomb and calls the spot it flies to; see GipfaeliLaunchRig.
    public static final DeferredItem<Item> GIPFAELI_LAUNCH_RIG = ITEMS.registerSimpleItem("gipfaeli_launch_rig",
            p -> p.stacksTo(1));

    // A pastry with a pin in it; see GipfaeliHandGrenade for the throw and GipfaeliGrenade for the rest.
    public static final DeferredItem<Item> GIPFAELI_GRENADE = ITEMS.registerSimpleItem("gipfaeli_grenade",
            p -> p.stacksTo(16));

    // The army's guns. What each one does is in GipfaeliWeapon; all any of them is here is an item
    // to hold, because the gun in a soldier's hand is the only record of what it is carrying.
    public static final DeferredItem<Item> LETONY_MATE_AK47 = ITEMS.registerSimpleItem("letony_mate_ak47",
            p -> p.stacksTo(1));
    public static final DeferredItem<Item> GIPFAELI_SHOTGUN = ITEMS.registerSimpleItem("gipfaeli_shotgun",
            p -> p.stacksTo(1));
    public static final DeferredItem<Item> GIPFAELI_MARKSMAN = ITEMS.registerSimpleItem("gipfaeli_marksman",
            p -> p.stacksTo(1));
    // The Panzer soldier's heavy machine gun, and the marcher's banner: the last two kits.
    public static final DeferredItem<Item> GIPFAELI_HEAVY_MG = ITEMS.registerSimpleItem("gipfaeli_heavy_mg",
            p -> p.stacksTo(1));
    public static final DeferredItem<Item> GIPFAELI_WAR_BANNER = ITEMS.registerSimpleItem("gipfaeli_war_banner",
            p -> p.stacksTo(1));

    // Signs soldiers on and tells them where to go; see GipfaeliCommandFlag and GipfaeliArmy.
    public static final DeferredItem<Item> GIPFAELI_COMMAND_FLAG = ITEMS.registerSimpleItem("gipfaeli_command_flag",
            p -> p.stacksTo(1));

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public GipfeliArmyMod(IEventBus modEventBus, ModContainer modContainer) {
        // The old names first, before anything is registered under the new; see LEGACY_NAMESPACE.
        aliasLegacyNames(BLOCKS);
        aliasLegacyNames(ITEMS);
        aliasLegacyNames(ENTITY_TYPES);
        aliasLegacyNames(BLOCK_ENTITY_TYPES);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so entity types get registered
        ENTITY_TYPES.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        GipfaeliSoldierMenu.MENUS.register(modEventBus);
        // The Last Prism and the Terraria bosses, which register themselves; see TerrariaContent.
        TerrariaContent.register(modEventBus);

        // Register ourselves for the game events the @SubscribeEvent methods below handle.
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(GipfaeliLock.class);
        NeoForge.EVENT_BUS.register(GipfaeliLaunchRig.class);
        NeoForge.EVENT_BUS.register(TerritoryManager.class);
        NeoForge.EVENT_BUS.register(TerritoryBorders.class);
        NeoForge.EVENT_BUS.addListener(TerritoryCommands::register);

        // The packets the territory screen and the server trade; see TerritoryNetwork.
        modEventBus.addListener(TerritoryNetwork::register);
        // And the one the screen sends the army; see GipfaeliArmyNetwork.
        modEventBus.addListener(GipfaeliArmyNetwork::register);
        NeoForge.EVENT_BUS.register(GipfaeliArmy.class);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Give the army's soldiers the health, speed and reach every mob needs to be built with
        modEventBus.addListener(this::registerEntityAttributes);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us.
        // A first start on a machine that still has the combat mod's old config file takes the
        // values that moved here out of it first; the file is loaded after every mod is constructed.
        LegacyConfig.migrate();
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    // Every entry in the register, reachable under the combat mod's namespace as well as this one's:
    // a lookup of the old name finds nothing under it and falls through to the alias.
    private static void aliasLegacyNames(DeferredRegister<?> register) {
        for (DeferredHolder<?, ?> entry : register.getEntries()) {
            Identifier id = entry.getId();
            register.addAlias(Identifier.fromNamespaceAndPath(LEGACY_NAMESPACE, id.getPath()), id);
        }
    }

    // Hand our items out to the vanilla creative tabs they belong in
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            // Kept out of the tab while the feature is off, so nobody is handed a launcher that will
            // not fire. The items stay registered either way - pulling them out of the registry would
            // strip them from any world that already had one.
            if (Config.on(Config.ENABLE_GIPFAELI)) {
                event.accept(GIPFAELI_LAUNCHER);
            }
            if (Config.on(Config.ENABLE_GIPFAELI_BOMB)) {
                event.accept(GIPFAELI_LAUNCH_RIG);
            }
            if (Config.on(Config.ENABLE_GIPFAELI_EXPLOSIVES)) {
                event.accept(GIPFAELI_GRENADE);
                event.accept(GIPFAELI_TNT_ITEM);
                event.accept(GIPFAELI_ULTRA_TNT_ITEM);
            }
            if (Config.on(Config.ENABLE_GIPFAELI_ARMY)) {
                event.accept(LETONY_MATE_AK47);
                event.accept(GIPFAELI_SHOTGUN);
                event.accept(GIPFAELI_MARKSMAN);
                event.accept(GIPFAELI_HEAVY_MG);
                event.accept(GIPFAELI_WAR_BANNER);
                event.accept(GIPFAELI_COMMAND_FLAG);
                event.accept(GIPFAELI_GUARD_POST_ITEM);
            }
        }
        // The TNT is a block that redstone lights, so it belongs with vanilla's TNT as well.
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS && Config.on(Config.ENABLE_GIPFAELI_EXPLOSIVES)) {
            event.accept(GIPFAELI_TNT_ITEM);
            event.accept(GIPFAELI_ULTRA_TNT_ITEM);
        }
        // The pastry is ammunition for both of them, so either one being on is reason to stock it.
        if (event.getTabKey() == CreativeModeTabs.FOOD_AND_DRINKS
                && (Config.on(Config.ENABLE_GIPFAELI)
                        || Config.on(Config.ENABLE_GIPFAELI_BOMB)
                        || Config.on(Config.ENABLE_GIPFAELI_ARMY))) {
            event.accept(GIPFAELI);
        }
    }

    // A mob is nothing without these: how much health it has, how fast it walks, how far it can
    // see. They are built once, here, rather than carried on the entity.
    private void registerEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(GIPFAELI_SOLDIER.get(), GipfaeliSoldier.createAttributes().build());
    }

    // The army's orders, which are also what the target menu's buttons run (see GipfaeliArmy).
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        GipfaeliArmyCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        if (GipfaeliLauncher.use(player, stack, event.getLevel())
                || GipfaeliLaunchRig.use(player, stack, event.getLevel())
                || GipfaeliCommandFlag.use(player, stack, event.getLevel())
                || GipfaeliHandGrenade.use(player, stack, event.getLevel())
                || GipfaeliWeapon.use(player, stack, event.getLevel())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    // Sighting something means right-clicking while pointing at it, which is the one case the two
    // events above never see: a click that lands on an entity arrives here instead. The command
    // flag wants that case too, because pointing at something is how the army is aimed.
    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (GipfaeliLauncher.use(event.getEntity(), event.getItemStack(), event.getLevel())
                || GipfaeliLaunchRig.use(event.getEntity(), event.getItemStack(), event.getLevel())
                || GipfaeliCommandFlag.useOn(event.getEntity(), event.getItemStack(), event.getTarget(), event.getLevel())
                || GipfaeliHandGrenade.use(event.getEntity(), event.getItemStack(), event.getLevel())
                || GipfaeliWeapon.use(event.getEntity(), event.getItemStack(), event.getLevel())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        // The flag on a guard post stations soldiers there rather than pointing them somewhere.
        if (player instanceof ServerPlayer commander && GipfaeliGuardPost.useFlag(commander, stack, event.getPos())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        if (GipfaeliLauncher.use(player, stack, event.getLevel())
                || GipfaeliLaunchRig.use(player, stack, event.getLevel())
                || GipfaeliCommandFlag.use(player, stack, event.getLevel())
                || GipfaeliHandGrenade.use(player, stack, event.getLevel())
                || GipfaeliWeapon.use(player, stack, event.getLevel())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Pre event) {
        GipfaeliLock.tick(event.getEntity());
        GipfaeliLaunchRig.tick(event.getEntity());
        GipfaeliRecoil.tick(event.getEntity());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        GipfaeliArmy.tick(event.getServer());
        GipfaeliAssault.tick(event.getServer());
    }
}
