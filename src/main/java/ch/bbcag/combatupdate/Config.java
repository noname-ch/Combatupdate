package ch.bbcag.combatupdate;

import net.neoforged.neoforge.common.ModConfigSpec;

// Server-side balance knobs for the mod's combat additions. Grouped by feature so the
// generated config screen (see CombatUpdateClient) reads as sections rather than a flat list.
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

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

    // --- Shortbow enchantment ---

    public static final ModConfigSpec.IntValue SHORTBOW_COOLDOWN_TICKS = BUILDER
            .comment("Cooldown, in ticks, before a bow enchanted with Shortbow can instant-fire again (20 ticks = 1 second)")
            .defineInRange("shortbowCooldownTicks", 20, 0, 1200);

    // --- TNT ---

    public static final ModConfigSpec.DoubleValue TNT_BLAST_RADIUS = BUILDER
            .comment("Explosion power of primed TNT, vanilla and modded alike (this is what vanilla calls explosion power; default 4.0)")
            .defineInRange("tntBlastRadius", 4.0, 0.0, 128.0);

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

    // --- Battering Ram enchantment ---

    public static final ModConfigSpec.DoubleValue RAM_MIN_SPEED = BUILDER
            .comment("How fast the player has to be gliding, in blocks per tick, before Battering Ram triggers at all (a rocket-boosted dive runs at roughly 1.5)")
            .defineInRange("ramMinSpeed", 0.8, 0.1, 10.0);

    public static final ModConfigSpec.DoubleValue RAM_DAMAGE_PER_SPEED = BUILDER
            .comment("Damage a Battering Ram impact deals per level, for every block per tick of impact speed")
            .defineInRange("ramDamagePerSpeed", 4.0, 0.1, 50.0);

    public static final ModConfigSpec.DoubleValue RAM_KNOCKBACK = BUILDER
            .comment("How hard a Battering Ram impact throws its target on along the flight path, per level")
            .defineInRange("ramKnockback", 1.0, 0.0, 10.0);

    public static final ModConfigSpec.IntValue RAM_SPEED_LOSS = BUILDER
            .comment("What percentage of their own speed a Battering Ram impact costs the player")
            .defineInRange("ramSpeedLoss", 60, 0, 100);

    public static final ModConfigSpec.IntValue RAM_COOLDOWN_TICKS = BUILDER
            .comment("How many ticks have to pass between Battering Ram impacts (20 ticks = 1 second)")
            .defineInRange("ramCooldownTicks", 20, 1, 200);

    public static final ModConfigSpec.IntValue RAM_HELMET_DAMAGE = BUILDER
            .comment("How much durability a Battering Ram impact costs the helmet")
            .defineInRange("ramHelmetDamage", 3, 0, 100);

    public static final ModConfigSpec.DoubleValue RAM_WIND_BURST_RADIUS = BUILDER
            .comment("Radius of the wind burst a Battering Ram impact sets off, against walls as well as mobs (a thrown wind charge is 1.2, the vanilla Wind Burst enchantment is 3.5); 0 turns the burst off entirely")
            .defineInRange("ramWindBurstRadius", 3.0, 0.0, 10.0);

    public static final ModConfigSpec.DoubleValue RAM_WIND_BURST_KNOCKBACK = BUILDER
            .comment("How hard the wind burst shoves at Battering Ram I; every level above that adds 0.5, matching the vanilla Wind Burst enchantment")
            .defineInRange("ramWindBurstKnockback", 1.2, 0.0, 10.0);

    public static final ModConfigSpec.DoubleValue RAM_EXPLOSION_POWER = BUILDER
            .comment("Explosion power of the real blast a Battering Ram impact sets off on top of the wind burst (TNT is 4.0); 0 turns the blast off entirely")
            .defineInRange("ramExplosionPower", 1.0, 0.0, 10.0);

    public static final ModConfigSpec.BooleanValue RAM_EXPLOSION_BREAKS_BLOCKS = BUILDER
            .comment("Whether the Battering Ram blast breaks terrain like TNT does, rather than only dealing damage and knockback")
            .define("ramExplosionBreaksBlocks", false);

    // --- Singularity enchantment ---

    public static final ModConfigSpec.DoubleValue SINGULARITY_DAMAGE_CAP_HEARTS = BUILDER
            .comment("The most damage, in hearts, that a single hit can deal to a player wearing Singularity leggings")
            .defineInRange("singularityDamageCapHearts", 4.0, 0.5, 20.0);

    // --- Armageddon enchantment ---

    public static final ModConfigSpec.IntValue ARMAGEDDON_STREAK_REQUIRED = BUILDER
            .comment("How many hits an Armageddon boots wearer has to land (or take, if the target is wearing them) before the next hit is empowered")
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

    // --- Floating damage numbers ---

    public static final ModConfigSpec.BooleanValue DAMAGE_NUMBERS = BUILDER
            .comment("Whether damage a player deals is shown as a number floating beside whatever they hit")
            .define("damageNumbers", true);

    public static final ModConfigSpec.DoubleValue DAMAGE_NUMBER_MINIMUM = BUILDER
            .comment("Hits costing less health than this are not worth a number")
            .defineInRange("damageNumberMinimum", 0.5, 0.0, 100.0);

    public static final ModConfigSpec.IntValue DAMAGE_NUMBER_LIFETIME_TICKS = BUILDER
            .comment("How many ticks a damage number stays up before it clears away (20 ticks = 1 second)")
            .defineInRange("damageNumberLifetimeTicks", 20, 1, 200);

    public static final ModConfigSpec.DoubleValue DAMAGE_NUMBER_RISE = BUILDER
            .comment("How far, in blocks, a damage number drifts upwards each tick")
            .defineInRange("damageNumberRise", 0.03, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue DAMAGE_NUMBER_SPREAD = BUILDER
            .comment("How widely damage numbers are scattered around the entity they belong to, so a flurry of hits doesn't stack them all on the one spot")
            .defineInRange("damageNumberSpread", 0.6, 0.0, 4.0);

    public static final ModConfigSpec.DoubleValue DAMAGE_NUMBER_SCALE = BUILDER
            .comment("How large a damage number is drawn (1.0 is the size of a name tag)")
            .defineInRange("damageNumberScale", 0.5, 0.1, 4.0);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
