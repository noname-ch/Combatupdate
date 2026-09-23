package ch.bbcag.combatupdate;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import ch.bbcag.combatupdate.combat.CombatEnchantmentHandler;
import ch.bbcag.combatupdate.combat.LeatherEnchantColor;
import ch.bbcag.combatupdate.enchantment.BatteringRam;
import ch.bbcag.combatupdate.enchantment.ShortbowEnchantmentHandler;
import ch.bbcag.combatupdate.entity.CombatFireball;
import ch.bbcag.combatupdate.entity.Exobeam;
import ch.bbcag.combatupdate.entity.GipfaeliBomb;
import ch.bbcag.combatupdate.entity.GipfaeliBullet;
import ch.bbcag.combatupdate.entity.GipfaeliGrenade;
import ch.bbcag.combatupdate.entity.GipfaeliRocket;
import ch.bbcag.combatupdate.entity.GipfaeliSoldier;
import ch.bbcag.combatupdate.entity.GipfaeliTnt;
import ch.bbcag.combatupdate.entity.ScarletBullet;
import ch.bbcag.combatupdate.entity.ScarletSpear;
import ch.bbcag.combatupdate.mixin.PrimedTntAccessor;
import ch.bbcag.combatupdate.territory.TerritoryBorders;
import ch.bbcag.combatupdate.territory.TerritoryCommands;
import ch.bbcag.combatupdate.territory.TerritoryManager;
import ch.bbcag.combatupdate.territory.TerritoryNetwork;

//i want to push this shit asap
// The value here should match =an entry in the META-INF/neoforge.mods.toml file
@Mod(CombatUpdate.MODID)
public final class CombatUpdate {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "combatupdate";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "combatupdate" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "combatupdate" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold Entity Types which will all be registered under the "combatupdate" namespace
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    // And one for block entities, of which the guard post is so far the only one
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    // And one for sounds of our own, which vanilla has nothing to stand in for
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(Registries.SOUND_EVENT, MODID);

    // The reading a Meat Obelisk gets when it is set down; the recording itself is wired up in sounds.json.
    public static final DeferredHolder<SoundEvent, SoundEvent> MEAT_OBELISK_SPEECH = SOUND_EVENTS.register("block.meat_obelisk.place",
            SoundEvent::createVariableRangeEvent);

    // The projectile entity thrown fire charges turn into; see CombatFireball for its constant-speed, explosive behavior
    public static final DeferredHolder<EntityType<?>, EntityType<CombatFireball>> COMBAT_FIREBALL = ENTITY_TYPES.register("combat_fireball",
            () -> EntityType.Builder.<CombatFireball>of(CombatFireball::new, MobCategory.MISC)
                    .noLootTable()
                    .sized(1.0F, 1.0F)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MODID, "combat_fireball"))));

    // The pastry the launcher throws; see GipfaeliRocket for what it does on arrival
    public static final DeferredHolder<EntityType<?>, EntityType<GipfaeliRocket>> GIPFAELI_ROCKET = ENTITY_TYPES.register("gipfaeli_rocket",
            () -> EntityType.Builder.<GipfaeliRocket>of(GipfaeliRocket::new, MobCategory.MISC)
                    .noLootTable()
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(8)
                    // Tighter than the fireball's 10, because this one can turn: the client has to be
                    // told where it actually went rather than left extrapolating a straight line.
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

    // The streak of light a full Exoblade swing throws; see Exobeam for how it picks what to chase
    public static final DeferredHolder<EntityType<?>, EntityType<Exobeam>> EXOBEAM = ENTITY_TYPES.register("exobeam",
            () -> EntityType.Builder.<Exobeam>of(Exobeam::new, MobCategory.MISC)
                    .noLootTable()
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(8)
                    // Tight, like the rocket's: it homes, so the client has to be told where it turned.
                    .updateInterval(2)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MODID, "exobeam"))));

    // The spear of light a Scarlet Devil throws, and the homing bullets it sheds; see ScarletSpear
    public static final DeferredHolder<EntityType<?>, EntityType<ScarletSpear>> SCARLET_SPEAR = ENTITY_TYPES.register("scarlet_spear",
            () -> EntityType.Builder.<ScarletSpear>of(ScarletSpear::new, MobCategory.MISC)
                    .noLootTable()
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(8)
                    .updateInterval(2)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MODID, "scarlet_spear"))));

    public static final DeferredHolder<EntityType<?>, EntityType<ScarletBullet>> SCARLET_BULLET = ENTITY_TYPES.register("scarlet_bullet",
            () -> EntityType.Builder.<ScarletBullet>of(ScarletBullet::new, MobCategory.MISC)
                    .noLootTable()
                    .sized(0.3F, 0.3F)
                    .clientTrackingRange(8)
                    // Tight: it homes, so the client has to be told where it turned.
                    .updateInterval(2)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MODID, "scarlet_bullet"))));

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

    // Nine porkchops as one block of deli ham; see MeatObelisk for what it says when it is set down.
    public static final DeferredBlock<MeatObelisk> MEAT_OBELISK = BLOCKS.registerBlock("meat_obelisk",
            MeatObelisk::new,
            p -> p.mapColor(MapColor.COLOR_PINK).strength(0.8F).sound(SoundType.SLIME_BLOCK));
    public static final DeferredItem<BlockItem> MEAT_OBELISK_ITEM = ITEMS.registerSimpleBlockItem("meat_obelisk", MEAT_OBELISK);

    // Swords with no swing timer and less damage behind each hit; see Shortsword for the trade
    public static final DeferredItem<Item> IRON_SHORTSWORD = ITEMS.registerSimpleItem("iron_shortsword",
            p -> Shortsword.properties(p, ToolMaterial.IRON));
    public static final DeferredItem<Item> DIAMOND_SHORTSWORD = ITEMS.registerSimpleItem("diamond_shortsword",
            p -> Shortsword.properties(p, ToolMaterial.DIAMOND));

    // An endgame sword that throws homing beams and lunges; see Exoblade.
    public static final DeferredItem<Item> EXOBLADE = ITEMS.registerSimpleItem("exoblade",
            Exoblade::properties);

    // An endgame spear, thrown like a trident but never used up; see ScarletDevil.
    public static final DeferredItem<ScarletDevil> SCARLET_DEVIL = ITEMS.registerItem("scarlet_devil",
            ScarletDevil::new, ScarletDevil::properties);

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
    public CombatUpdate(IEventBus modEventBus, ModContainer modContainer) {
        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so entity types get registered
        ENTITY_TYPES.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        SOUND_EVENTS.register(modEventBus);
        GipfaeliSoldierMenu.MENUS.register(modEventBus);

        // Register ourselves for the game events the @SubscribeEvent methods below handle.
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(ShortbowEnchantmentHandler.class);
        NeoForge.EVENT_BUS.register(CombatEnchantmentHandler.class);
        NeoForge.EVENT_BUS.register(GipfaeliLock.class);
        NeoForge.EVENT_BUS.register(GipfaeliLaunchRig.class);
        NeoForge.EVENT_BUS.register(Exoblade.class);
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

        // Retune the reach of vanilla melee weapons
        modEventBus.addListener(WeaponReach::modifyDefaultComponents);

        // Let swords block again, weaker than a shield
        modEventBus.addListener(SwordBlocking::modifyDefaultComponents);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    // Hand our items out to the vanilla creative tabs they belong in
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(MEAT_OBELISK_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(IRON_SHORTSWORD);
            event.accept(DIAMOND_SHORTSWORD);
            if (Config.on(Config.ENABLE_EXOBLADE)) {
                event.accept(EXOBLADE);
            }
            if (Config.on(Config.ENABLE_SCARLET_DEVIL)) {
                event.accept(SCARLET_DEVIL);
            }
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
        if (throwFireballIfHeld(player, stack, event.getLevel())
                || ElytraBomb.release(player, stack, event.getLevel())
                || Exoblade.use(player, stack, event.getLevel())
                || GipfaeliLauncher.use(player, stack, event.getLevel())
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
        if (Exoblade.use(event.getEntity(), event.getItemStack(), event.getLevel())
                || GipfaeliLauncher.use(event.getEntity(), event.getItemStack(), event.getLevel())
                || GipfaeliLaunchRig.use(event.getEntity(), event.getItemStack(), event.getLevel())
                || GipfaeliCommandFlag.useOn(event.getEntity(), event.getItemStack(), event.getTarget(), event.getLevel())
                || GipfaeliHandGrenade.use(event.getEntity(), event.getItemStack(), event.getLevel())
                || GipfaeliWeapon.use(event.getEntity(), event.getItemStack(), event.getLevel())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    // Holding a vanilla fire charge and right-clicking throws it as a fireball instead of placing fire
    // on a block; holding TNT while gliding drops it as a bomb instead of placing it.
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        if (player instanceof ServerPlayer commander && GipfaeliGuardPost.useFlag(commander, stack, event.getPos())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        if (throwFireballIfHeld(player, stack, event.getLevel())
                || ElytraBomb.release(player, stack, event.getLevel())
                || Exoblade.use(player, stack, event.getLevel())
                || GipfaeliLauncher.use(player, stack, event.getLevel())
                || GipfaeliLaunchRig.use(player, stack, event.getLevel())
                || GipfaeliCommandFlag.use(player, stack, event.getLevel())
                || GipfaeliHandGrenade.use(player, stack, event.getLevel())
                || GipfaeliWeapon.use(player, stack, event.getLevel())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    private boolean throwFireballIfHeld(Player player, ItemStack stack, Level level) {
        if (!Config.on(Config.ENABLE_FIREBALL)
                || !stack.is(Items.FIRE_CHARGE)
                || player.getCooldowns().isOnCooldown(stack)) {
            return false;
        }

        if (level instanceof ServerLevel serverLevel) {
            Vec3 eyePosition = player.getEyePosition();
            Vec3 lookAngle = player.getLookAngle();
            CombatFireball fireball = new CombatFireball(serverLevel, player, lookAngle);
            fireball.setPos(eyePosition.x + lookAngle.x, eyePosition.y + lookAngle.y, eyePosition.z + lookAngle.z);
            serverLevel.addFreshEntity(fireball);
        }

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.0F, 1.0F);

        stack.consume(1, player);
        player.getCooldowns().addCooldown(stack, Config.FIREBALL_COOLDOWN_TICKS.getAsInt());
        return true;
    }

    // PrimedTnt bakes its explosion power in at spawn with no vanilla hook to change it later,
    // so this overwrites it (via PrimedTntAccessor) as soon as the entity joins the level.
    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!Config.on(Config.ENABLE_TNT_TUNING)) {
            return;
        }

        // Gipfaeli TNT sets its own power when it goes off (see GipfaeliTnt#explode), so the vanilla
        // retune is kept off it: this knob is for vanilla TNT.
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof PrimedTnt tnt && !(tnt instanceof GipfaeliTnt)) {
            ((PrimedTntAccessor) tnt).setExplosionPower((float) Config.TNT_BLAST_RADIUS.getAsDouble());
        }
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Pre event) {
        ElytraBoost.tick(event.getEntity());
        BatteringRam.tick(event.getEntity());
        LeatherEnchantColor.tick(event.getEntity());
        GipfaeliLock.tick(event.getEntity());
        GipfaeliLaunchRig.tick(event.getEntity());
        GipfaeliRecoil.tick(event.getEntity());
        Exoblade.tick(event.getEntity());
    }

    // Post rather than Pre: by then the hit has been through armour, resistance and absorption, so
    // getHealthDamage is the health the target actually lost rather than what was aimed at it.
    @SubscribeEvent
    public void onLivingDamage(LivingDamageEvent.Post event) {
        if (event.getSource().getEntity() instanceof Player) {
            DamageNumbers.spawn(event.getEntity(), event.getHealthDamage());
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        DamageNumbers.tick();
        GipfaeliArmy.tick(event.getServer());
    }
}
