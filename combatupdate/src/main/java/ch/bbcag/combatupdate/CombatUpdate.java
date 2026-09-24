package ch.bbcag.combatupdate;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.level.Level;
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
import ch.bbcag.combatupdate.entity.ScarletBullet;
import ch.bbcag.combatupdate.entity.ScarletSpear;
import ch.bbcag.combatupdate.entity.ZenithBlade;
import ch.bbcag.combatupdate.entity.TrainingDummy;
import ch.bbcag.combatupdate.mixin.PrimedTntAccessor;

// The value here should match an entry in the META-INF/neoforge.mods.toml file.
//
// This is the combat mod on its own: melee, flight, the enchantments, movement and the endgame
// weapons. Territory, the Gipfaeli arsenal and the army are the Gipfaeli Army mod (../Gipfeliarmy),
// which loads beside this one and shares no code with it.
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

    // The streak of light a full Exoblade swing throws; see Exobeam for how it picks what to chase
    public static final DeferredHolder<EntityType<?>, EntityType<Exobeam>> EXOBEAM = ENTITY_TYPES.register("exobeam",
            () -> EntityType.Builder.<Exobeam>of(Exobeam::new, MobCategory.MISC)
                    .noLootTable()
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(8)
                    // Tighter than the fireball's 10: it homes, so the client has to be told where it
                    // turned rather than left extrapolating a straight line.
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

    // The phantom swords a Zenith swing sends looping out and back; see ZenithBlade
    public static final DeferredHolder<EntityType<?>, EntityType<ZenithBlade>> ZENITH_BLADE = ENTITY_TYPES.register("zenith_blade",
            () -> EntityType.Builder.<ZenithBlade>of(ZenithBlade::new, MobCategory.MISC)
                    .noLootTable()
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(8)
                    // Loose, unlike the other projectiles: the client flies its own copy along the same
                    // loop, and a position update only ever drags it back a tick.
                    .updateInterval(20)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MODID, "zenith_blade"))));

    // Something to test weapons on that stays put and never dies; see TrainingDummy.
    public static final DeferredHolder<EntityType<?>, EntityType<TrainingDummy>> TRAINING_DUMMY = ENTITY_TYPES.register("training_dummy",
            () -> EntityType.Builder.of(TrainingDummy::new, MobCategory.MISC)
                    // Picked up whole rather than broken (see TrainingDummy#mobInteract), so nothing to roll for.
                    .noLootTable()
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MODID, "training_dummy"))));

    // Nine porkchops as one block of deli ham; see MeatObelisk for what it says when it is set down.
    public static final DeferredBlock<MeatObelisk> MEAT_OBELISK = BLOCKS.registerBlock("meat_obelisk",
            MeatObelisk::new,
            p -> p.mapColor(MapColor.COLOR_PINK).strength(0.8F).sound(SoundType.SLIME_BLOCK));
    public static final DeferredItem<BlockItem> MEAT_OBELISK_ITEM = ITEMS.registerSimpleBlockItem("meat_obelisk", MEAT_OBELISK);

    // Sets a training dummy down; see TrainingDummyItem.
    public static final DeferredItem<TrainingDummyItem> TRAINING_DUMMY_ITEM = ITEMS.registerItem("training_dummy",
            TrainingDummyItem::new, p -> p.stacksTo(16));

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

    // Terraria's endgame sword, forged from all the others, that throws looping phantoms of them; see Zenith.
    public static final DeferredItem<Item> ZENITH = ITEMS.registerSimpleItem("zenith",
            Zenith::properties);

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public CombatUpdate(IEventBus modEventBus, ModContainer modContainer) {
        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so entity types get registered
        ENTITY_TYPES.register(modEventBus);
        SOUND_EVENTS.register(modEventBus);

        // Register ourselves for the game events the @SubscribeEvent methods below handle.
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(ShortbowEnchantmentHandler.class);
        NeoForge.EVENT_BUS.register(CombatEnchantmentHandler.class);
        NeoForge.EVENT_BUS.register(Exoblade.class);
        NeoForge.EVENT_BUS.register(Zenith.class);
        NeoForge.EVENT_BUS.register(TrainingDummy.class);

        // The packet the dash and slide send; see Movement.
        modEventBus.addListener(Movement::register);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Give the training dummy the health, speed and reach every mob needs to be built with
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
            // Kept out of the tab while the feature is off, so nobody is handed a sword that does
            // nothing special. The items stay registered either way - pulling them out of the
            // registry would strip them from any world that already had one.
            if (Config.on(Config.ENABLE_EXOBLADE)) {
                event.accept(EXOBLADE);
            }
            if (Config.on(Config.ENABLE_SCARLET_DEVIL)) {
                event.accept(SCARLET_DEVIL);
            }
            if (Config.on(Config.ENABLE_ZENITH)) {
                event.accept(ZENITH);
            }
            if (Config.on(Config.ENABLE_TRAINING_DUMMY)) {
                event.accept(TRAINING_DUMMY_ITEM);
            }
        }
    }

    // A mob is nothing without these: how much health it has, how fast it walks, how far it can
    // see. They are built once, here, rather than carried on the entity.
    private void registerEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(TRAINING_DUMMY.get(), TrainingDummy.createAttributes().build());
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        if (throwFireballIfHeld(player, stack, event.getLevel())
                || ElytraBomb.release(player, stack, event.getLevel())
                || Exoblade.use(player, stack, event.getLevel())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    // A right-click that lands on an entity arrives here rather than at either of the other two
    // events, and a lunge is aimed by pointing at something, so the Exoblade wants that case too.
    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (Exoblade.use(event.getEntity(), event.getItemStack(), event.getLevel())) {
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
        if (throwFireballIfHeld(player, stack, event.getLevel())
                || ElytraBomb.release(player, stack, event.getLevel())
                || Exoblade.use(player, stack, event.getLevel())) {
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

        // Vanilla TNT only, by type rather than by class: modded TNT that extends PrimedTnt (the
        // Gipfaeli kinds, for one) sets its own power when it goes off, and this knob is not for it.
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof PrimedTnt tnt && tnt.getType() == EntityTypes.TNT) {
            ((PrimedTntAccessor) tnt).setExplosionPower((float) Config.TNT_BLAST_RADIUS.getAsDouble());
        }
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Pre event) {
        ElytraBoost.tick(event.getEntity());
        Movement.tick(event.getEntity());
        BatteringRam.tick(event.getEntity());
        LeatherEnchantColor.tick(event.getEntity());
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
        Exoblade.tickSlashes();
    }
}
