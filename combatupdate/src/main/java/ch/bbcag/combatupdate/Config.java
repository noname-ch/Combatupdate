package ch.bbcag.combatupdate;

import net.neoforged.neoforge.common.ModConfigSpec;

// Server-side balance knobs for the mod's combat additions.
//
// Everything sits inside a pushed section, because the generated config screen (see
// CombatUpdateClient) turns each one into a button that opens a page of its own. One level deep
// and no further: the point is to open Elytra and see the elytra settings, not to go hunting
// through nested groups for them.
//
// The first of those pages is Features, and it is the odd one out: every other page holds the
// numbers behind one part of the mod, while Features holds nothing but the on/off switch for each
// part of it. Someone who wants free look but not sword blocking has one list to read rather than
// seven pages to open, and the numbers stay where they are worth reading - next to each other,
// where a change to one can be weighed against the rest.
//
// Sections are what the config file is divided into as well, so moving an entry between them
// moves it between tables in combatupdate-common.toml and an existing file loses that value.
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // Deliberately left at the root instead of pushed into a section, so it sits on the front page of
    // the config screen rather than behind a button. It is the switch that turns the mod off; it
    // should be the first thing anyone looking for that finds.
    public static final ModConfigSpec.BooleanValue MOD_ENABLED = BUILDER
            .comment("Master switch. Turn this off and every change below goes with it, leaving vanilla behaviour behind. The mod's own items and enchantments stay registered either way, so worlds already holding them still load")
            .define("modEnabled", true);

    // Its own page in the config screen, and the first one: every switch that turns a single part of
    // the mod on or off, in one list. Read Config.on for what "off" costs a feature - the master
    // switch above is folded into the same check, so nothing here has to repeat it.
    static {
        BUILDER.comment("Every part of the mod, switched on or off one at a time. Turn one off and it leaves vanilla behaviour behind; the numbers that tune it stay on its own page, waiting for it to come back.").push("features");
    }
    // --- Elytra ---


    public static final ModConfigSpec.BooleanValue ENABLE_FREE_LOOK = BUILDER
            .comment("Whether gliding steers through a full 3D orientation - roll and pitch on the movement keys, no 90-degree limit looking up or down. Off leaves vanilla's yaw/pitch aiming alone")
            .define("enableFreeLook", true);

    public static final ModConfigSpec.BooleanValue ENABLE_SPEED_TUNING = BUILDER
            .comment("Whether glide speed is retuned at all. Off restores vanilla's speed and also drops the penalty a fused chestplate carries, since both are the same adjustment")
            .define("enableSpeedTuning", true);

    public static final ModConfigSpec.BooleanValue ENABLE_BOOST = BUILDER
            .comment("Whether holding shift while gliding burns firework rockets straight out of the inventory for a continuous boost")
            .define("enableBoost", true);
    // --- Movement ---


    public static final ModConfigSpec.BooleanValue ENABLE_DASH = BUILDER
            .comment("Whether the dash key throws the player a few blocks along the way they are walking, on the ground or once per jump in the air")
            .define("enableDash", true);

    public static final ModConfigSpec.BooleanValue ENABLE_SLIDE = BUILDER
            .comment("Whether pressing sneak while sprinting drops the player into a slide that keeps their speed, gains more downhill and fits under one-block gaps; pressed in the air, it starts on landing")
            .define("enableSlide", true);
    // --- Explosives ---


    public static final ModConfigSpec.BooleanValue ENABLE_FIREBALL = BUILDER
            .comment("Whether right-clicking a fire charge throws it as an exploding fireball. Off puts it back to placing fire on a block")
            .define("enableFireball", true);

    public static final ModConfigSpec.BooleanValue ENABLE_ELYTRA_BOMB = BUILDER
            .comment("Whether right-clicking TNT while gliding releases it as a bomb. Off puts it back to placing the block, gliding or not")
            .define("enableElytraBomb", true);

    public static final ModConfigSpec.BooleanValue ENABLE_TNT_TUNING = BUILDER
            .comment("Whether primed TNT is retuned to the blast radius on the Explosives page. Off leaves every TNT at whatever power it was spawned with")
            .define("enableTntTuning", true);
    // --- Melee ---


    public static final ModConfigSpec.BooleanValue ENABLE_WEAPON_REACH = BUILDER
            .comment("Whether tridents reach further and axes reach less far than the other melee weapons. Off puts every vanilla weapon back on the player's own 3-block interaction range")
            .define("enableWeaponReach", true);

    public static final ModConfigSpec.BooleanValue ENABLE_SWORD_BLOCKING = BUILDER
            .comment("Whether holding right-click with a vanilla sword raises it to soak part of a frontal hit, pre-1.9 style. Off leaves swords with no block at all")
            .define("enableSwordBlocking", true);

    public static final ModConfigSpec.BooleanValue ENABLE_EXOBLADE = BUILDER
            .comment("Whether the Exoblade throws homing beams on full-strength swings and lunges on right-click. Off leaves it an ordinary (if very sharp) sword and takes it out of the creative tab; the item stays registered, so a world already holding one still loads")
            .define("enableExoblade", true);

    public static final ModConfigSpec.BooleanValue ENABLE_SCARLET_DEVIL = BUILDER
            .comment("Whether the Scarlet Devil can be thrown. Off leaves it a spear that only stabs and takes it out of the creative tab; the item stays registered, so a world already holding one still loads")
            .define("enableScarletDevil", true);

    public static final ModConfigSpec.BooleanValue ENABLE_ZENITH = BUILDER
            .comment("Whether the Zenith throws its looping phantom swords on full-strength swings. Off leaves it an ordinary (if very fast) sword and takes it out of the creative tab; the item stays registered, so a world already holding one still loads")
            .define("enableZenith", true);

    public static final ModConfigSpec.BooleanValue ENABLE_TRAINING_DUMMY = BUILDER
            .comment("Whether a training dummy can be set down. Off also takes it out of the creative tab; dummies already standing stay where they are, and the item stays registered, so a world already holding one still loads")
            .define("enableTrainingDummy", true);
    // --- Enchantments ---


    public static final ModConfigSpec.BooleanValue ENABLE_BATTERING_RAM = BUILDER
            .comment("Whether the Battering Ram enchantment does anything. Off also hands walls back their fly-into-wall damage and stops the helmet rendering flattened, since all three read the same enchantment level")
            .define("enableBatteringRam", true);

    public static final ModConfigSpec.BooleanValue ENABLE_ENCHANTMENTS = BUILDER
            .comment("Whether the combat perk enchantments do anything - Regularity, Singularity, Armageddon, Lifesteal, Combo: Perun, Gamble and Mirror. Off leaves them enchantable and inert")
            .define("enableEnchantments", true);

    public static final ModConfigSpec.BooleanValue ENABLE_SHORTBOW = BUILDER
            .comment("Whether the Shortbow enchantment does anything")
            .define("enableShortbow", true);

    public static final ModConfigSpec.BooleanValue ENABLE_LEATHER_ENCHANT_COLORS = BUILDER
            .comment("Whether leather leggings and boots are recoloured to show which combat enchantment they carry. Off leaves whatever dye is already on them; it does not put back a colour this has overwritten")
            .define("enableLeatherEnchantColors", true);

    public static final ModConfigSpec.BooleanValue ENABLE_BOOK_TEXTURES = BUILDER
            .comment("Whether an enchanted book holding one of the mod's enchantments shows that enchantment's own cover. Off draws every enchanted book as vanilla's. Only changes how books look, so each player can set it for themselves")
            .define("enableBookTextures", true);
    // --- Floating damage numbers ---


    public static final ModConfigSpec.BooleanValue ENABLE_DAMAGE_NUMBERS = BUILDER
            .comment("Whether damage a player deals is shown as a number floating beside whatever they hit")
            .define("enableDamageNumbers", true);
    static {
        BUILDER.pop();
    }

    // Its own page in the config screen: a pushed section renders as a button that
    // opens a screen showing only what is inside it.
    static {
        BUILDER.comment("Elytra flight: how it steers, how fast it goes, and what fusing one into a chestplate costs. Switched on and off on the Features page.").push("elytra");
    }
    // --- Elytra ---


    public static final ModConfigSpec.IntValue ROLL_SPEED = BUILDER
            .comment("How many degrees per second holding A/D rolls the player while gliding with an elytra")
            .defineInRange("rollSpeed", 200, 10, 720);

    public static final ModConfigSpec.IntValue PITCH_SPEED = BUILDER
            .comment("How many degrees per second holding W/S pitches the player while gliding with an elytra (S pulls the nose up, W pushes it down)")
            .defineInRange("pitchSpeed", 120, 10, 720);

    public static final ModConfigSpec.DoubleValue CONTROL_RAMP_SECONDS = BUILDER
            .comment("How long, in seconds, the A/D and W/S keys take to wind the glide's rotation up to full speed and back down again (0 is an instant, unsmoothed response)")
            .defineInRange("controlRampSeconds", 0.15, 0.0, 2.0);

    public static final ModConfigSpec.DoubleValue ELYTRA_SPEED_MULTIPLIER = BUILDER
            .comment("Multiplies the top speed of elytra gliding (1.0 is vanilla)")
            .defineInRange("elytraSpeedMultiplier", 1.0, 0.5, 5.0);

    public static final ModConfigSpec.IntValue BOOST_ROCKET_INTERVAL_TICKS = BUILDER
            .comment("How many ticks of shift-boosting a single firework rocket pays for (20 ticks = 1 second)")
            .defineInRange("boostRocketIntervalTicks", 20, 1, 200);
    // --- Elytra fused into a chestplate ---


    public static final ModConfigSpec.IntValue FUSED_ELYTRA_PENALTY_LIGHT = BUILDER
            .comment("Percentage of glide speed lost when the elytra is fused into a light chestplate (gold, by default; see the combatupdate:fused_elytra/light item tag)")
            .defineInRange("fusedElytraPenaltyLight", 5, 0, 90);

    public static final ModConfigSpec.IntValue FUSED_ELYTRA_PENALTY_NORMAL = BUILDER
            .comment("Percentage of glide speed lost when the elytra is fused into any chestplate that is neither light nor heavy (leather, chainmail, iron, and modded armour)")
            .defineInRange("fusedElytraPenaltyNormal", 10, 0, 90);

    public static final ModConfigSpec.IntValue FUSED_ELYTRA_PENALTY_HEAVY = BUILDER
            .comment("Percentage of glide speed lost when the elytra is fused into a heavy chestplate (diamond and netherite, by default; see the combatupdate:fused_elytra/heavy item tag)")
            .defineInRange("fusedElytraPenaltyHeavy", 20, 0, 90);
    static {
        BUILDER.pop();
    }

    // Its own page in the config screen: a pushed section renders as a button that
    // opens a screen showing only what is inside it.
    static {
        BUILDER.comment("The Battering Ram helmet enchantment: what an impact does, and what it costs the wearer. Switched on and off on the Features page.").push("batteringRam");
    }
    // --- Battering Ram enchantment ---


    public static final ModConfigSpec.DoubleValue RAM_MIN_SPEED = BUILDER
            .comment("How fast the player has to be gliding, in blocks per tick, before Battering Ram triggers at all (a rocket-boosted dive runs at roughly 1.5)")
            .defineInRange("ramMinSpeed", 0.8, 0.1, 10.0);

    public static final ModConfigSpec.DoubleValue RAM_DAMAGE_PER_SPEED = BUILDER
            .comment("Damage a Battering Ram impact deals for every block per tick of impact speed. This does not scale with the enchantment's level - higher levels buy force rather than damage")
            .defineInRange("ramDamagePerSpeed", 4.0, 0.1, 50.0);

    public static final ModConfigSpec.DoubleValue RAM_KNOCKBACK = BUILDER
            .comment("How hard a Battering Ram impact throws its target on along the flight path, per level")
            .defineInRange("ramKnockback", 1.0, 0.0, 10.0);

    public static final ModConfigSpec.IntValue RAM_SPEED_LOSS = BUILDER
            .comment("What percentage of their own speed a Battering Ram impact costs the player at level I; every level above that costs 20 points less, so a better helmet carries more of the charge through the hit")
            .defineInRange("ramSpeedLoss", 60, 0, 100);

    public static final ModConfigSpec.IntValue RAM_COOLDOWN_TICKS = BUILDER
            .comment("How many ticks have to pass between Battering Ram impacts (20 ticks = 1 second)")
            .defineInRange("ramCooldownTicks", 20, 1, 200);

    public static final ModConfigSpec.IntValue RAM_HELMET_DAMAGE = BUILDER
            .comment("How much durability a Battering Ram impact costs the helmet")
            .defineInRange("ramHelmetDamage", 3, 0, 100);

    public static final ModConfigSpec.DoubleValue RAM_WIND_BURST_RADIUS = BUILDER
            .comment("Radius of the wind burst a Battering Ram impact sets off at level I, against walls as well as mobs, growing by 1.0 per level above that (a thrown wind charge is 1.2, the vanilla Wind Burst enchantment is 3.5); 0 turns the burst off entirely")
            .defineInRange("ramWindBurstRadius", 3.0, 0.0, 10.0);

    public static final ModConfigSpec.DoubleValue RAM_WIND_BURST_KNOCKBACK = BUILDER
            .comment("How hard the wind burst shoves at Battering Ram I; every level above that adds 0.5, matching the vanilla Wind Burst enchantment")
            .defineInRange("ramWindBurstKnockback", 1.2, 0.0, 10.0);

    public static final ModConfigSpec.IntValue RAM_STUN_TICKS = BUILDER
            .comment("How long, in ticks, an impact that costs the player all of their speed leaves them dazed - blind and barely able to move (20 ticks = 1 second). A partial loss dazes them for proportionally less, so a high-level ram that barely slows you barely stuns you either, while a wall taken flat out always earns the full daze; 0 turns it off")
            .defineInRange("ramStunTicks", 20, 0, 200);

    public static final ModConfigSpec.DoubleValue RAM_EXPLOSION_POWER = BUILDER
            .comment("Explosion power of the real blast a Battering Ram impact sets off on top of the wind burst, at level I, growing by 0.75 per level above that (TNT is 4.0); 0 turns the blast off entirely")
            .defineInRange("ramExplosionPower", 1.0, 0.0, 10.0);

    public static final ModConfigSpec.BooleanValue RAM_EXPLOSION_BREAKS_BLOCKS = BUILDER
            .comment("Whether the Battering Ram blast breaks terrain like TNT does, rather than only dealing damage and knockback")
            .define("ramExplosionBreaksBlocks", false);
    static {
        BUILDER.pop();
    }

    // Its own page in the config screen: a pushed section renders as a button that
    // opens a screen showing only what is inside it.
    static {
        BUILDER.comment("The mod's other enchantments. Switched on and off on the Features page.").push("enchantments");
    }
    // --- Shortbow enchantment ---


    public static final ModConfigSpec.IntValue SHORTBOW_COOLDOWN_TICKS = BUILDER
            .comment("Cooldown, in ticks, before a bow enchanted with Shortbow can instant-fire again (20 ticks = 1 second)")
            .defineInRange("shortbowCooldownTicks", 20, 0, 1200);
    // --- Singularity enchantment ---


    public static final ModConfigSpec.DoubleValue SINGULARITY_DAMAGE_CAP_HEARTS = BUILDER
            .comment("The most damage, in hearts, that a single hit can deal to a player wearing Singularity leggings")
            .defineInRange("singularityDamageCapHearts", 4.0, 0.5, 20.0);
    // --- Armageddon enchantment ---


    public static final ModConfigSpec.IntValue ARMAGEDDON_STREAK_REQUIRED = BUILDER
            .comment("How many hits an Armageddon boots wearer has to take from players, without landing one of their own, before their next hit is empowered")
            .defineInRange("armageddonStreakRequired", 3, 1, 10);

    public static final ModConfigSpec.DoubleValue ARMAGEDDON_DAMAGE_BONUS_PERCENT = BUILDER
            .comment("Bonus damage on an Armageddon-empowered hit, as a fraction of the hit's normal damage (0.5 = +50%)")
            .defineInRange("armageddonDamageBonusPercent", 0.5, 0.0, 5.0);
    // --- Regularity enchantment ---


    public static final ModConfigSpec.DoubleValue REGULARITY_THRESHOLD_HEARTS = BUILDER
            .comment("Regularity only follows up on hits smaller than this, in hearts - a heavy blow doesn't get a delayed second hit")
            .defineInRange("regularityThresholdHearts", 4.0, 0.5, 20.0);

    public static final ModConfigSpec.DoubleValue REGULARITY_SECOND_HIT_PERCENT = BUILDER
            .comment("Regularity's delayed second hit, as a fraction of the damage the first hit dealt (0.5 = 50%)")
            .defineInRange("regularitySecondHitPercent", 0.5, 0.0, 1.0);
    // --- Lifesteal enchantment ---


    public static final ModConfigSpec.DoubleValue LIFESTEAL_MAX_HEAL_HEARTS = BUILDER
            .comment("The most a single Lifesteal hit can heal its wielder, in hearts")
            .defineInRange("lifestealMaxHealHearts", 3.0, 0.0, 20.0);
    // --- Gamble enchantment ---


    public static final ModConfigSpec.DoubleValue GAMBLE_CHANCE = BUILDER
            .comment("Chance, per hit, that Gamble triggers its true-damage coin flip (0.15 = 15%)")
            .defineInRange("gambleChance", 0.15, 0.0, 1.0);
    // --- Leather enchant colors (Hypixel Pit style) ---


    public static final ModConfigSpec.IntValue RAGE_PANTS_COLOR = BUILDER
            .comment("Dye color (0xRRGGBB) leather leggings turn while enchanted with Regularity")
            .defineInRange("ragePantsColor", 0xE60026, 0, 0xFFFFFF);

    public static final ModConfigSpec.IntValue SINGULARITY_PANTS_COLOR = BUILDER
            .comment("Dye color (0xRRGGBB) leather leggings turn while enchanted with Singularity")
            .defineInRange("singularityPantsColor", 0x1F4FFF, 0, 0xFFFFFF);

    public static final ModConfigSpec.IntValue ARMAGEDDON_BOOTS_COLOR = BUILDER
            .comment("Dye color (0xRRGGBB) leather boots turn while enchanted with Armageddon")
            .defineInRange("armageddonBootsColor", 0x8B0000, 0, 0xFFFFFF);
    static {
        BUILDER.pop();
    }

    // Its own page in the config screen: a pushed section renders as a button that
    // opens a screen showing only what is inside it.
    static {
        BUILDER.comment("Thrown fire charges and TNT. Switched on and off on the Features page.").push("explosives");
    }
    // --- Fireball (thrown fire charge) ---


    public static final ModConfigSpec.IntValue FIREBALL_COOLDOWN_TICKS = BUILDER
            .comment("How many ticks a player must wait between throwing fire charges as fireballs (10 ticks = 0.5 seconds)")
            .defineInRange("fireballCooldownTicks", 10, 0, 1200);

    public static final ModConfigSpec.DoubleValue FIREBALL_SPEED = BUILDER
            .comment("How fast a thrown fireball travels, in blocks per tick, at a constant speed (no acceleration)")
            .defineInRange("fireballSpeed", 3.0, 0.1, 10.0);

    public static final ModConfigSpec.DoubleValue FIREBALL_EXPLOSION_POWER = BUILDER
            .comment("The explosion power of a thrown fireball on impact (TNT is 4.0)")
            .defineInRange("fireballExplosionPower", 2.0, 0.0, 10.0);
    // --- TNT ---


    public static final ModConfigSpec.DoubleValue TNT_BLAST_RADIUS = BUILDER
            .comment("Explosion power of vanilla primed TNT (this is what vanilla calls explosion power; default 4.0). Modded TNT is left at its own power: the Gipfaeli kinds, for one, set theirs as they go off")
            .defineInRange("tntBlastRadius", 4.0, 0.0, 128.0);

    // --- TNT dropped as a bomb while gliding ---

    public static final ModConfigSpec.DoubleValue BOMB_MOMENTUM_TRANSFER = BUILDER
            .comment("How much of the player's own velocity a bomb dropped from an elytra keeps (1.0 is all of it, 0.0 drops it straight down). Vanilla air drag bleeds this off at 2% a tick from there, so a fast run throws the bomb a long way forward")
            .defineInRange("bombMomentumTransfer", 1.0, 0.0, 2.0);

    public static final ModConfigSpec.IntValue BOMB_FUSE_TICKS = BUILDER
            .comment("How long the fuse is on a bomb dropped from an elytra, in ticks - how long it has to fall before it goes off (a lit block of TNT is 80; 20 ticks = 1 second)")
            .defineInRange("bombFuseTicks", 60, 1, 400);

    public static final ModConfigSpec.IntValue BOMB_COOLDOWN_TICKS = BUILDER
            .comment("How many ticks must pass between dropping one bomb and the next")
            .defineInRange("bombCooldownTicks", 10, 0, 200);
    static {
        BUILDER.pop();
    }

    // Its own page in the config screen: a pushed section renders as a button that
    // opens a screen showing only what is inside it.
    static {
        BUILDER.comment("The Exoblade: how hard its beams hit and how far they look for something to chase, and what its lunge does. Switched on and off on the Features page.").push("exoblade");
    }
    // --- Exobeam (thrown by a full-strength swing) ---


    public static final ModConfigSpec.DoubleValue EXOBEAM_DAMAGE = BUILDER
            .comment("How much damage an Exobeam deals to whatever it hits (the blade itself hits for 14)")
            .defineInRange("exobeamDamage", 8.0, 0.0, 100.0);

    public static final ModConfigSpec.DoubleValue EXOBEAM_SPEED = BUILDER
            .comment("How fast an Exobeam flies, in blocks per tick, at a constant speed. It gives out after 2 seconds, so this also sets how far it can reach")
            .defineInRange("exobeamSpeed", 1.2, 0.1, 5.0);

    public static final ModConfigSpec.DoubleValue EXOBEAM_HOMING_RANGE = BUILDER
            .comment("How far, in blocks, an Exobeam looks for a monster or player to bend towards. 0 makes every beam fly straight")
            .defineInRange("exobeamHomingRange", 16.0, 0.0, 64.0);

    public static final ModConfigSpec.DoubleValue EXOBLADE_SWING_CUT_DAMAGE = BUILDER
            .comment("How much damage a full-strength swing deals to every other monster and player in the arc in front of you, besides whatever it was aimed at. 0 makes swings hit only what they are aimed at")
            .defineInRange("exobladeSwingCutDamage", 8.0, 0.0, 100.0);

    public static final ModConfigSpec.DoubleValue EXOBEAM_SLASH_DAMAGE = BUILDER
            .comment("How much damage each of the three Exo slashes that follow an Exobeam hit deals. 0 turns the slashes off")
            .defineInRange("exobeamSlashDamage", 3.0, 0.0, 100.0);
    // --- Lunge (right-click) ---


    public static final ModConfigSpec.DoubleValue EXOBLADE_DASH_SPEED = BUILDER
            .comment("How fast the right-click lunge carries the player, in blocks per tick. It lasts 6 ticks, so the default covers about 9 blocks")
            .defineInRange("exobladeDashSpeed", 1.5, 0.1, 5.0);

    public static final ModConfigSpec.DoubleValue EXOBLADE_DASH_DAMAGE = BUILDER
            .comment("How much damage the first thing a lunge runs into takes before the player bounces back off it")
            .defineInRange("exobladeDashDamage", 20.0, 0.0, 200.0);

    public static final ModConfigSpec.IntValue EXOBLADE_DASH_COOLDOWN_TICKS = BUILDER
            .comment("How many ticks must pass between one lunge and the next (20 ticks = 1 second)")
            .defineInRange("exobladeDashCooldownTicks", 40, 0, 1200);
    // --- Big slash (the first swing after a lunge lands) ---


    public static final ModConfigSpec.DoubleValue EXOBLADE_BIG_SLASH_DAMAGE = BUILDER
            .comment("How much damage the big slash deals to every monster and player in the arc in front of you. It also throws three Exobeams")
            .defineInRange("exobladeBigSlashDamage", 24.0, 0.0, 200.0);

    public static final ModConfigSpec.IntValue EXOBLADE_BIG_SLASH_WINDOW_TICKS = BUILDER
            .comment("How many ticks after a lunge lands the next swing stays a big slash (20 ticks = 1 second). 0 turns the big slash off")
            .defineInRange("exobladeBigSlashWindowTicks", 40, 0, 400);
    static {
        BUILDER.pop();
    }

    // Its own page in the config screen: a pushed section renders as a button that
    // opens a screen showing only what is inside it.
    static {
        BUILDER.comment("The Scarlet Devil: its spear, the Scarlet Blast where the spear lands, the bullets it sheds, and the fully charged Gungnir throw. Switched on and off on the Features page.").push("scarletDevil");
    }
    // --- Spear ---


    public static final ModConfigSpec.DoubleValue SCARLET_SPEAR_DAMAGE = BUILDER
            .comment("How much damage the thrown spear deals to each thing it passes through (a Gungnir throw deals double)")
            .defineInRange("scarletSpearDamage", 14.0, 0.0, 200.0);

    public static final ModConfigSpec.DoubleValue SCARLET_SPEAR_SPEED = BUILDER
            .comment("How fast the spear flies, in blocks per tick. It gives out after 1.5 seconds, so this also sets how far it can reach")
            .defineInRange("scarletSpearSpeed", 4.0, 0.5, 8.0);

    public static final ModConfigSpec.IntValue SCARLET_DEVIL_COOLDOWN_TICKS = BUILDER
            .comment("How many ticks must pass between one throw and the next (20 ticks = 1 second). Holding right-click throws again as soon as this runs out")
            .defineInRange("scarletDevilCooldownTicks", 12, 0, 1200);
    // --- Scarlet Blast ---


    public static final ModConfigSpec.DoubleValue SCARLET_BLAST_DAMAGE = BUILDER
            .comment("How much damage the Scarlet Blast deals to everything caught in it, the thrower and their allies aside. It goes off wherever the spear strikes a creature or a wall")
            .defineInRange("scarletBlastDamage", 8.0, 0.0, 200.0);

    public static final ModConfigSpec.DoubleValue SCARLET_BLAST_RADIUS = BUILDER
            .comment("How far, in blocks, the Scarlet Blast reaches (a Gungnir throw's reaches twice as far). It never breaks blocks")
            .defineInRange("scarletBlastRadius", 3.0, 0.0, 16.0);
    // --- Scarlet bullets ---


    public static final ModConfigSpec.DoubleValue SCARLET_BULLET_DAMAGE = BUILDER
            .comment("How much damage each of the homing bullets the spear sheds in flight deals. Only monsters and other players are chased or hit by them")
            .defineInRange("scarletBulletDamage", 3.0, 0.0, 100.0);
    // --- Gungnir (fully charged throw) ---


    public static final ModConfigSpec.IntValue GUNGNIR_CHARGE_TICKS = BUILDER
            .comment("How long, in ticks, you have to go without throwing before the next throw is a Gungnir (Calamity's stealth strike): a bigger spear that hits twice as hard, sheds bullets three times as fast, blasts twice as wide and heals its thrower")
            .defineInRange("gungnirChargeTicks", 40, 10, 400);

    public static final ModConfigSpec.DoubleValue GUNGNIR_HEAL = BUILDER
            .comment("How much health (2 = one heart) the thrower gets back for each creature a Gungnir spear strikes")
            .defineInRange("gungnirHeal", 2.0, 0.0, 40.0);
    static {
        BUILDER.pop();
    }

    // Its own page in the config screen: a pushed section renders as a button that
    // opens a screen showing only what is inside it.
    static {
        BUILDER.comment("The Zenith: how hard its phantom swords cut, how many each swing throws, and how far out they loop. Switched on and off on the Features page.").push("zenith");
    }
    // --- Phantom swords (thrown by a full-strength swing) ---


    public static final ModConfigSpec.DoubleValue ZENITH_BLADE_DAMAGE = BUILDER
            .comment("How much damage a phantom sword deals to each thing it cuts through (the Zenith itself hits for 12). Each phantom cuts each creature once, on its way out or back")
            .defineInRange("zenithBladeDamage", 8.0, 0.0, 200.0);

    public static final ModConfigSpec.IntValue ZENITH_BLADES_PER_SWING = BUILDER
            .comment("How many phantom swords each full-strength swing throws, each on a loop of its own. The Zenith swings 2.4 times a second")
            .defineInRange("zenithBladesPerSwing", 2, 1, 8);

    public static final ModConfigSpec.DoubleValue ZENITH_REACH = BUILDER
            .comment("How far out, in blocks, the phantoms loop at most: to whatever you are aiming at within this range, or this far along your look if nothing is in the way")
            .defineInRange("zenithReach", 16.0, 4.0, 48.0);
    static {
        BUILDER.pop();
    }

    // Its own page in the config screen: a pushed section renders as a button that
    // opens a screen showing only what is inside it.
    static {
        BUILDER.comment("The numbers that float up off whatever a player hits. Switched on and off on the Features page.").push("damageNumbers");
    }
    // --- Floating damage numbers ---


    public static final ModConfigSpec.DoubleValue DAMAGE_NUMBER_MINIMUM = BUILDER
            .comment("Hits costing less health than this are not worth a number")
            .defineInRange("damageNumberMinimum", 0.5, 0.0, 100.0);

    public static final ModConfigSpec.IntValue DAMAGE_NUMBER_LIFETIME_TICKS = BUILDER
            .comment("How many ticks a damage number stays up before it clears away (20 ticks = 1 second)")
            .defineInRange("damageNumberLifetimeTicks", 20, 1, 200);

    public static final ModConfigSpec.DoubleValue DAMAGE_NUMBER_RISE = BUILDER
            .comment("How far, in blocks, a damage number drifts upwards each tick")
            .defineInRange("damageNumberRise", 0.07, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue DAMAGE_NUMBER_SPREAD = BUILDER
            .comment("How widely damage numbers are scattered around the entity they belong to, so a flurry of hits doesn't stack them all on the one spot")
            .defineInRange("damageNumberSpread", 0.6, 0.0, 4.0);

    public static final ModConfigSpec.DoubleValue DAMAGE_NUMBER_SCALE = BUILDER
            .comment("How large a damage number is drawn (1.0 is the size of a name tag)")
            .defineInRange("damageNumberScale", 0.8, 0.1, 4.0);
    static {
        BUILDER.pop();
    }

    // Its own page in the config screen: a pushed section renders as a button that
    // opens a screen showing only what is inside it.
    static {
        BUILDER.comment("The dash and the slide. Switched on and off on the Features page.").push("movement");
    }
    // --- Dash ---


    public static final ModConfigSpec.DoubleValue DASH_SPEED = BUILDER
            .comment("How fast a dash carries the player, in blocks per tick. A dash holds this speed for 4 ticks, so 1.2 covers about 5 blocks; sprinting is about 0.28")
            .defineInRange("dashSpeed", 1.2, 0.3, 3.0);

    public static final ModConfigSpec.IntValue DASH_COOLDOWN_TICKS = BUILDER
            .comment("How many ticks must pass between one dash and the next (20 ticks = 1 second)")
            .defineInRange("dashCooldownTicks", 30, 0, 1200);

    public static final ModConfigSpec.IntValue AIR_DASHES = BUILDER
            .comment("How many dashes a player gets in the air before they have to land again. 0 keeps the dash on the ground")
            .defineInRange("airDashes", 1, 0, 10);
    // --- Slide ---


    public static final ModConfigSpec.DoubleValue SLIDE_SPEED = BUILDER
            .comment("How fast a slide starts, in blocks per tick. It slows down from there until it is back to walking pace; sprinting is about 0.28")
            .defineInRange("slideSpeed", 0.7, 0.3, 2.0);

    public static final ModConfigSpec.IntValue SLIDE_DURATION_TICKS = BUILDER
            .comment("The longest a slide lasts, in ticks, if nothing ends it sooner (20 ticks = 1 second)")
            .defineInRange("slideDurationTicks", 20, 5, 100);

    public static final ModConfigSpec.IntValue SLIDE_COOLDOWN_TICKS = BUILDER
            .comment("How many ticks must pass after a slide ends before the next one can start (20 ticks = 1 second)")
            .defineInRange("slideCooldownTicks", 10, 0, 1200);
    static {
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    // Every feature gate is read through here, so the master switch covers all of them and no caller
    // has to remember to check it as well.
    //
    // The unloaded case is not defensive padding: weapon reach and sword blocking are applied while
    // the game is still starting up, and a ModConfigSpec value throws outright rather than falling
    // back to its default when it is read before the file has been loaded.
    public static boolean on(ModConfigSpec.BooleanValue feature) {
        if (!SPEC.isLoaded()) {
            return feature.getDefault();
        }

        return MOD_ENABLED.get() && feature.get();
    }

    private Config() {
    }
}
