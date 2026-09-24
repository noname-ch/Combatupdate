package ch.bbcag.gipfeliarmy;

import net.neoforged.neoforge.common.ModConfigSpec;

// Server-side balance knobs for the Gipfaeli Army mod: territory, the arsenal, and the army itself.
//
// Everything sits inside a pushed section, because the generated config screen (see
// GipfeliArmyClient) turns each one into a button that opens a page of its own. One level deep
// and no further: the point is to open Elytra and see the elytra settings, not to go hunting
// through nested groups for them.
//
// The first of those pages is Features, and it is the odd one out: every other page holds the
// numbers behind one part of the mod, while Features holds nothing but the on/off switch for each
// part of it. Someone who wants the launcher but not territory has one list to read rather than
// four pages to open, and the numbers stay where they are worth reading - next to each other,
// where a change to one can be weighed against the rest.
//
// Sections are what the config file is divided into as well, so moving an entry between them
// moves it between tables in gipfeliarmy-common.toml and an existing file loses that value.
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // Deliberately left at the root instead of pushed into a section, so it sits on the front page of
    // the config screen rather than behind a button. It is the switch that turns the mod off; it
    // should be the first thing anyone looking for that finds.
    public static final ModConfigSpec.BooleanValue MOD_ENABLED = BUILDER
            .comment("Master switch. Turn this off and every change below goes with it, leaving vanilla behaviour behind. The mod's own items, blocks and soldiers stay registered either way, so worlds already holding them still load")
            .define("modEnabled", true);

    // Its own page in the config screen, and the first one: every switch that turns a single part of
    // the mod on or off, in one list. Read Config.on for what "off" costs a feature - the master
    // switch above is folded into the same check, so nothing here has to repeat it.
    static {
        BUILDER.comment("Every part of the mod, switched on or off one at a time. Turn one off and it leaves vanilla behaviour behind; the numbers that tune it stay on its own page, waiting for it to come back.").push("features");
    }
    // --- Gipfaeli launcher and launch rig ---


    public static final ModConfigSpec.BooleanValue ENABLE_GIPFAELI = BUILDER
            .comment("Whether the Gipfaeli launcher fires at all. Off also takes it and its ammo out of the creative tab; both items stay registered, so a world already holding one still loads")
            .define("enableGipfaeli", true);

    public static final ModConfigSpec.BooleanValue ENABLE_GIPFAELI_BOMB = BUILDER
            .comment("Whether the Gipfaeli launch rig sets bombs down and calls strikes with them. Off also takes it out of the creative tab; the item stays registered, so a world already holding one still loads")
            .define("enableGipfaeliBomb", true);

    public static final ModConfigSpec.BooleanValue ENABLE_GIPFAELI_EXPLOSIVES = BUILDER
            .comment("Whether the Gipfaeli hand grenade can be thrown and Gipfaeli TNT (plain and Ultra) can be lit. Off also takes them out of the creative tab; the items and blocks stay registered, so a world already holding them still loads")
            .define("enableGipfaeliExplosives", true);
    // --- Territory ---


    public static final ModConfigSpec.BooleanValue ENABLE_TERRITORY = BUILDER
            .comment("Whether chunks can be claimed as territory, kept from other players, and captured off their owner. Off leaves every chunk open to everyone; claims already made stay on disk and come back when it is turned on again")
            .define("enableTerritory", true);
    // --- Terraria ---


    public static final ModConfigSpec.BooleanValue ENABLE_LAST_PRISM = BUILDER
            .comment("Whether the Last Prism (both the rainbow one and the Random one) fires its beams. Off also takes both out of the creative tab; the items stay registered, so a world already holding one still loads")
            .define("enableLastPrism", true);

    public static final ModConfigSpec.BooleanValue ENABLE_TERRARIA_BOSSES = BUILDER
            .comment("Whether the Eye of Cthulhu, the Wall of Flesh and Plantera have lairs in the overworld, wake when a player walks in, and show up as waypoints on the HUD. Off stops lairs being built and bosses waking; lairs already built stay standing")
            .define("enableTerrariaBosses", true);
    static {
        BUILDER.pop();
    }

    // Its own page in the config screen: a pushed section renders as a button that
    // opens a screen showing only what is inside it.
    static {
        BUILDER.comment("The Gipfaeli launcher and the launch rig: what they throw, how hard it lands, how far they can reach, and how long a called strike takes to leave. Switched on and off on the Features page.").push("gipfaeli");
    }
    // --- Gipfaeli launcher ---


    public static final ModConfigSpec.BooleanValue GIPFAELI_CONSUMES_AMMO = BUILDER
            .comment("Whether firing spends a Gipfaeli out of the inventory. Off makes the launcher fire on its own, with nothing to bake")
            .define("gipfaeliConsumesAmmo", true);

    public static final ModConfigSpec.IntValue GIPFAELI_COOLDOWN_TICKS = BUILDER
            .comment("How many ticks must pass between shots (20 ticks = 1 second)")
            .defineInRange("gipfaeliCooldownTicks", 30, 0, 1200);

    public static final ModConfigSpec.DoubleValue GIPFAELI_SPEED = BUILDER
            .comment("How fast a launched Gipfaeli travels, in blocks per tick, at a constant speed (no acceleration)")
            .defineInRange("gipfaeliSpeed", 1.2, 0.1, 10.0);

    public static final ModConfigSpec.DoubleValue GIPFAELI_DAMAGE = BUILDER
            .comment("Damage a Gipfaeli deals to whatever it hits directly, on top of its blast")
            .defineInRange("gipfaeliDamage", 6.0, 0.0, 100.0);

    public static final ModConfigSpec.DoubleValue GIPFAELI_EXPLOSION_POWER = BUILDER
            .comment("Explosion power of a Gipfaeli on impact (TNT is 4.0); 0 turns the blast off and leaves only the direct hit")
            .defineInRange("gipfaeliExplosionPower", 2.0, 0.0, 10.0);

    public static final ModConfigSpec.BooleanValue GIPFAELI_BREAKS_BLOCKS = BUILDER
            .comment("Whether a Gipfaeli blast breaks terrain, rather than only dealing damage and knockback")
            .define("gipfaeliBreaksBlocks", false);
    // --- Lock-on sight ---


    public static final ModConfigSpec.DoubleValue GIPFAELI_LOCK_RANGE = BUILDER
            .comment("How far away, in blocks, a player or animal can be sighted and locked on to")
            .defineInRange("gipfaeliLockRange", 48.0, 4.0, 192.0);

    public static final ModConfigSpec.DoubleValue GIPFAELI_LOCK_CONE_DEGREES = BUILDER
            .comment("How far off the centre of the view something may sit and still be sighted, in degrees. Wider is easier to lock on to a distant target with, and easier to lock on to the wrong one with")
            .defineInRange("gipfaeliLockConeDegrees", 12.0, 1.0, 60.0);

    public static final ModConfigSpec.DoubleValue GIPFAELI_TURN_RATE = BUILDER
            .comment("How sharply a Gipfaeli in flight can steer towards the target it was locked on to, in degrees per tick. Low enough and a target that breaks hard to one side can throw it off; 0 turns homing off and leaves the launcher firing straight")
            .defineInRange("gipfaeliTurnRate", 9.0, 0.0, 90.0);

    public static final ModConfigSpec.BooleanValue GIPFAELI_AVOID_BLOCKS = BUILDER
            .comment("Whether a homing Gipfaeli steers around terrain in its way instead of burying itself in the first hill between it and its target. Off makes it fly the straight line and take whatever is on it")
            .define("gipfaeliAvoidBlocks", true);

    public static final ModConfigSpec.DoubleValue GIPFAELI_AVOID_LOOKAHEAD = BUILDER
            .comment("How far ahead, in blocks, a homing Gipfaeli looks for something solid to steer around. Further ahead starts the climb earlier and flies wider; nearer cuts it finer and clips more corners")
            .defineInRange("gipfaeliAvoidLookahead", 8.0, 1.0, 48.0);

    public static final ModConfigSpec.BooleanValue GIPFAELI_LOCK_NEEDS_LINE_OF_SIGHT = BUILDER
            .comment("Whether a target has to be in plain view to be locked on to. Off lets a lock be taken through walls")
            .define("gipfaeliLockNeedsLineOfSight", true);

    public static final ModConfigSpec.BooleanValue GIPFAELI_SHOW_SIGHT = BUILDER
            .comment("Whether the sight draws a reticle over the locked target")
            .define("gipfaeliShowSight", true);

    public static final ModConfigSpec.DoubleValue GIPFAELI_ZOOM = BUILDER
            .comment("How far the view pulls in while something is sighted, as a multiple (1.0 is no zoom at all, 4.0 is about a spyglass)")
            .defineInRange("gipfaeliZoom", 3.0, 1.0, 10.0);

    public static final ModConfigSpec.DoubleValue GIPFAELI_ZOOM_SECONDS = BUILDER
            .comment("How long the zoom takes to come in and go back out again, in seconds (0 snaps to it)")
            .defineInRange("gipfaeliZoomSeconds", 0.25, 0.0, 3.0);
    // --- Launch rig and its bombs ---


    public static final ModConfigSpec.IntValue GIPFAELI_BOMB_COUNTDOWN_TICKS = BUILDER
            .comment("How long a bomb counts down between being set down and leaving the pad, in ticks (20 ticks = 1 second). This is the whole warning anyone standing on the called block gets")
            .defineInRange("gipfaeliBombCountdownTicks", 100, 0, 1200);

    public static final ModConfigSpec.DoubleValue GIPFAELI_BOMB_RANGE = BUILDER
            .comment("How far away, in blocks, a block can be called as a target. This limits the aim, not the flight - carry the rig somewhere else before setting the bomb down and it still flies the whole way back. Reaching much past the server's simulation distance is asking for the bomb to fly into chunks nobody has loaded")
            .defineInRange("gipfaeliBombRange", 128.0, 16.0, 512.0);

    public static final ModConfigSpec.DoubleValue GIPFAELI_BOMB_FLIGHT_SPEED = BUILDER
            .comment("How fast a bomb crosses the ground towards the called block, in blocks per tick, which is what sets how long the flight takes. It lands on that block either way; this is how long anyone standing there has to get out of the way once they hear it go")
            .defineInRange("gipfaeliBombFlightSpeed", 1.5, 0.2, 10.0);

    public static final ModConfigSpec.DoubleValue GIPFAELI_BOMB_ARC = BUILDER
            .comment("How high the bomb climbs over the middle of its flight, as a fraction of the distance to the called block (0.4 is a mortar's lob, 0 is a flat throw). Nothing stops the bomb in the air either way - this is how the flight looks, not where it ends up. Never less than 6 blocks, however short the shot")
            .defineInRange("gipfaeliBombArc", 0.4, 0.0, 2.0);

    public static final ModConfigSpec.DoubleValue GIPFAELI_BOMB_EXPLOSION_POWER = BUILDER
            .comment("Explosion power of a bomb where it lands (TNT is 4.0). Larger than a fired Gipfaeli on purpose: this one took five seconds and a called spot to deliver")
            .defineInRange("gipfaeliBombExplosionPower", 6.0, 0.0, 20.0);

    public static final ModConfigSpec.BooleanValue GIPFAELI_BOMB_BREAKS_BLOCKS = BUILDER
            .comment("Whether a bomb's blast breaks terrain, rather than only dealing damage and knockback")
            .define("gipfaeliBombBreaksBlocks", false);
    static {
        BUILDER.pop();
    }

    // Its own page in the config screen: a pushed section renders as a button that
    // opens a screen showing only what is inside it.
    static {
        BUILDER.comment("The Gipfaeli hand grenade and the two Gipfaeli TNT blocks: how long each one takes to go off, how hard it goes, and whether the blast takes the terrain with it. Switched on and off on the Features page.").push("gipfaeliExplosives");
    }
    // --- Gipfaeli hand grenade ---


    public static final ModConfigSpec.IntValue GRENADE_FUSE_TICKS = BUILDER
            .comment("How many ticks a thrown grenade rolls about before it goes off (20 ticks = 1 second). It counts from the throw, not from where it lands")
            .defineInRange("grenadeFuseTicks", 40, 1, 400);

    public static final ModConfigSpec.DoubleValue GRENADE_THROW_SPEED = BUILDER
            .comment("How hard a grenade leaves the hand, in blocks per tick. A snowball is 1.5; gravity and a bounce or two take it from there")
            .defineInRange("grenadeThrowSpeed", 1.2, 0.1, 5.0);

    public static final ModConfigSpec.DoubleValue GRENADE_EXPLOSION_POWER = BUILDER
            .comment("Explosion power of a grenade where it goes off (TNT is 4.0). Smaller than TNT: it fits in a pocket and there are sixteen to a stack")
            .defineInRange("grenadeExplosionPower", 2.5, 0.0, 20.0);

    public static final ModConfigSpec.BooleanValue GRENADE_BREAKS_BLOCKS = BUILDER
            .comment("Whether a grenade's blast breaks terrain, rather than only dealing damage and knockback")
            .define("grenadeBreaksBlocks", false);

    public static final ModConfigSpec.IntValue GRENADE_COOLDOWN_TICKS = BUILDER
            .comment("How many ticks must pass between throws (20 ticks = 1 second)")
            .defineInRange("grenadeCooldownTicks", 15, 0, 1200);
    // --- Gipfaeli TNT ---


    public static final ModConfigSpec.IntValue GIPFAELI_TNT_FUSE_TICKS = BUILDER
            .comment("How many ticks lit Gipfaeli TNT burns before it goes off (vanilla TNT is 80)")
            .defineInRange("gipfaeliTntFuseTicks", 80, 1, 1200);

    public static final ModConfigSpec.DoubleValue GIPFAELI_TNT_EXPLOSION_POWER = BUILDER
            .comment("Explosion power of Gipfaeli TNT (vanilla TNT is 4.0)")
            .defineInRange("gipfaeliTntExplosionPower", 8.0, 0.0, 64.0);

    public static final ModConfigSpec.IntValue ULTRA_TNT_FUSE_TICKS = BUILDER
            .comment("How many ticks lit Gipfaeli Ultra TNT burns before it goes off. Longer than the plain kind on purpose: what it does deserves a head start")
            .defineInRange("ultraTntFuseTicks", 100, 1, 1200);

    public static final ModConfigSpec.DoubleValue ULTRA_TNT_EXPLOSION_POWER = BUILDER
            .comment("Explosion power of Gipfaeli Ultra TNT (vanilla TNT is 4.0). This is the first blast; the grenades it scatters are on top")
            .defineInRange("ultraTntExplosionPower", 16.0, 0.0, 128.0);

    public static final ModConfigSpec.IntValue ULTRA_TNT_GRENADES = BUILDER
            .comment("How many live Gipfaeli grenades Ultra TNT throws out when it goes off, each with a short fuse of its own. 0 makes it a plain, very large bang")
            .defineInRange("ultraTntGrenades", 12, 0, 64);

    public static final ModConfigSpec.BooleanValue GIPFAELI_TNT_BREAKS_BLOCKS = BUILDER
            .comment("Whether either Gipfaeli TNT's blast breaks terrain the way vanilla TNT does, rather than only dealing damage and knockback")
            .define("gipfaeliTntBreaksBlocks", true);
    static {
        BUILDER.pop();
    }

    // Its own page in the config screen: a pushed section renders as a button that
    // opens a screen showing only what is inside it.
    static {
        BUILDER.comment("The Gipfaeli army: what a soldier costs to sign on, what it can take, and how far it will go after something it was pointed at.").push("gipfaeliArmy");
    }
    // --- Gipfaeli army ---


    public static final ModConfigSpec.BooleanValue ENABLE_GIPFAELI_ARMY = BUILDER
            .comment("Whether the command flag recruits soldiers and gives them orders at all. Off also takes the flag and the guns out of the creative tab; every item stays registered, so a world already holding them still loads, and soldiers already standing in one stay where they are")
            .define("enableGipfaeliArmy", true);

    public static final ModConfigSpec.BooleanValue ARMY_CONSUMES_SUPPLIES = BUILDER
            .comment("Whether signing a soldier on spends the gun it is handed and the Gipfaeli it eats. Off raises an army out of nothing, and also stops a dismissed or fallen soldier handing its gun back, since there was never one to pay for")
            .define("armyConsumesSupplies", true);

    public static final ModConfigSpec.BooleanValue ARMY_DROPS_ON_DEATH = BUILDER
            .comment("Whether a fallen soldier leaves its gun and armour on the ground. Off by default: a war game between two squads should not bury the field in kit")
            .define("armyDropsOnDeath", false);

    public static final ModConfigSpec.IntValue ARMY_RECRUIT_RATIONS = BUILDER
            .comment("How many Gipfaeli a recruit eats on the way in, on top of the gun it is handed")
            .defineInRange("armyRecruitRations", 2, 0, 64);

    public static final ModConfigSpec.IntValue ARMY_MAX_SQUAD = BUILDER
            .comment("How many soldiers one player may have in all their squads together. Every one of them paths, shoots and is tracked by everybody nearby, so this is as much a budget for the server as it is a balance knob")
            .defineInRange("armyLimit", 200, 1, 2000);

    public static final ModConfigSpec.IntValue ARMY_SQUAD_SIZE = BUILDER
            .comment("How many soldiers one squad - one colour - holds, not counting its commander. Forty is eight ranks of five, which is what a squad stands as on parade")
            .defineInRange("armySquadSize", 40, 1, 200);

    public static final ModConfigSpec.DoubleValue ARMY_HEALTH_MULTIPLIER = BUILDER
            .comment("Multiplies the health every role is signed on with (a rifleman's is 20, a Panzer soldier's 44; 1.0 leaves each role at its own)")
            .defineInRange("armyHealthMultiplier", 1.0, 0.1, 10.0);

    public static final ModConfigSpec.DoubleValue ARMY_ARMOR_MULTIPLIER = BUILDER
            .comment("Multiplies the armour every role stands in (a rifleman's is 4, a Panzer soldier's 12; a full set of iron is 15)")
            .defineInRange("armyArmorMultiplier", 1.0, 0.0, 5.0);

    public static final ModConfigSpec.BooleanValue ARMY_AIMBOT = BUILDER
            .comment("Whether soldiers lead their shots - fire at where a moving target will be when the round arrives, rather than where it is now. Off has them shoot straight at the target the way a skeleton does, and miss anything that keeps moving")
            .define("armyAimbot", true);

    public static final ModConfigSpec.BooleanValue ARMY_BANNER_RALLY = BUILDER
            .comment("Whether a marcher's banner makes the squad and its commander faster, harder-hitting and tougher within ten blocks of it. Off leaves the marcher a soldier with no gun")
            .define("armyBannerRally", true);

    public static final ModConfigSpec.DoubleValue ARMY_WEAPON_DAMAGE = BUILDER
            .comment("Multiplies what every Gipfaeli gun deals, in a soldier's hands and in a player's alike (1.0 leaves each weapon at its own damage). The launcher is not included: what it throws is a Gipfaeli, and that one is tuned above")
            .defineInRange("armyWeaponDamageMultiplier", 1.0, 0.0, 10.0);

    public static final ModConfigSpec.DoubleValue WEAPON_RECOIL = BUILDER
            .comment("How far the muzzle of a gun climbs when it is fired, as a multiple of what each weapon is tuned for (1.0 as designed, 0 turns the climb off and leaves the crosshair where you put it). This moves the aim itself, not just the view, so it costs you the next shot - a shotgun throws it up hard, a rifle walks it up over a burst. The launcher climbs hardest of all")
            .defineInRange("weaponRecoil", 1.0, 0.0, 5.0);

    public static final ModConfigSpec.DoubleValue WEAPON_KNOCKBACK = BUILDER
            .comment("How hard a gun shoves the person firing it, as a multiple of what each weapon is tuned for (0 turns the shove off). The launcher's is heavy enough to ride: aim at your own feet and it will carry you somewhere")
            .defineInRange("weaponKnockback", 1.0, 0.0, 5.0);

    public static final ModConfigSpec.DoubleValue WEAPON_HEADSHOT = BUILDER
            .comment("What a round that lands on a target's head is worth, as a multiple of its normal damage (1.0 turns headshots off). Applies to every Gipfaeli gun, in a player's hands and a soldier's alike")
            .defineInRange("weaponHeadshotMultiplier", 2.0, 1.0, 10.0);

    public static final ModConfigSpec.DoubleValue ARMY_MARCH_RANGE = BUILDER
            .comment("How far away, in blocks, something can be and still be worth marching on. Past this an order lapses and the squad falls back in - which is what stops a target picked off the far end of the menu walking the whole army off the map")
            .defineInRange("armyMarchRange", 192.0, 16.0, 512.0);

    public static final ModConfigSpec.BooleanValue ARMY_GUARDS = BUILDER
            .comment("Whether soldiers take on monsters that come near of their own accord. Off leaves them firing only at what they were told to, and at whatever hits them or their commander first")
            .define("armyGuardsAgainstMonsters", true);

    public static final ModConfigSpec.BooleanValue ARMY_FRIENDLY_FIRE = BUILDER
            .comment("Whether rounds from a Gipfaeli gun hit the shooter's own side - their commander, and the rest of that commander's squad. Off, they pass straight through, which is the only thing that makes a squad standing shoulder to shoulder survivable")
            .define("armyFriendlyFire", false);

    public static final ModConfigSpec.DoubleValue ARMY_MENU_RANGE = BUILDER
            .comment("How far out, in blocks, the target menu looks for animals and monsters to list. Players on the server are always listed, however far off or however many worlds away they are")
            .defineInRange("armyMenuRange", 128.0, 16.0, 512.0);

    public static final ModConfigSpec.IntValue ARMY_MENU_ENTRIES = BUILDER
            .comment("How many targets the menu lists per group before it stops and says how many more there were")
            .defineInRange("armyMenuEntries", 10, 1, 50);
    // --- Parade ground ---


    public static final ModConfigSpec.IntValue ARMY_PARADE_WIDTH = BUILDER
            .comment("How many soldiers stand abreast in one block of a parade")
            .defineInRange("armyParadeWidth", 5, 1, 64);

    public static final ModConfigSpec.IntValue ARMY_PARADE_DEPTH = BUILDER
            .comment("How many ranks deep one block of a parade stands. Width times depth is how many soldiers go down before a second block falls in beside the first")
            .defineInRange("armyParadeDepth", 8, 1, 64);

    public static final ModConfigSpec.DoubleValue ARMY_PARADE_SPACING = BUILDER
            .comment("How far apart, in blocks, soldiers stand from one another in the ranks. 1.0 is shoulder to shoulder, a block each; larger opens the ranks up")
            .defineInRange("armyParadeSpacing", 1.0, 0.6, 8.0);

    public static final ModConfigSpec.DoubleValue ARMY_PARADE_BLOCK_GAP = BUILDER
            .comment("How wide a gap, in blocks, is left between one block of a parade and the next, on top of the spacing. One soldier's worth by default, so the blocks read apart")
            .defineInRange("armyParadeBlockGap", 2.0, 0.0, 16.0);

    public static final ModConfigSpec.DoubleValue ARMY_PARADE_STANDOFF = BUILDER
            .comment("How far, in blocks, the front rank of a parade stands from the commander who called it")
            .defineInRange("armyParadeStandoff", 3.0, 1.0, 32.0);
    static {
        BUILDER.pop();
    }

    // Its own page in the config screen: a pushed section renders as a button that
    // opens a screen showing only what is inside it.
    static {
        BUILDER.comment("Territory: how much of the map one player may claim, what a claim keeps out, and how long taking one off its owner takes. Switched on and off on the Features page.").push("territory");
    }
    // --- Territory ---


    public static final ModConfigSpec.IntValue TERRITORY_MAX_BLOCKS = BUILDER
            .comment("How much ground one player may hold at once, across every dimension, in blocks of area. Ground is still claimed a whole chunk at a time and every chunk counts as 256 (16 x 16), so this rounds down to a whole number of chunks: 2048 is 8 chunks, 1000 is 3")
            .defineInRange("territoryMaxBlocks", 2048, 256, 2_560_000);

    public static final ModConfigSpec.IntValue TERRITORY_CAPTURE_SECONDS = BUILDER
            .comment("How long, in seconds, a player has to stand in somebody else's chunk to take it off them. The owner is told the moment it starts, so this is the whole window they have to come and do something about it. 0 hands the chunk over on the spot")
            .defineInRange("territoryCaptureSeconds", 30, 0, 3600);

    public static final ModConfigSpec.BooleanValue TERRITORY_CAPTURE_NEEDS_OWNER_ONLINE = BUILDER
            .comment("Whether a chunk can only be captured while its owner is on the server. Off lets territory be taken from a player who is away; they are told the moment they next log in")
            .define("territoryCaptureNeedsOwnerOnline", false);

    public static final ModConfigSpec.BooleanValue TERRITORY_PROTECT_BLOCKS = BUILDER
            .comment("Whether anyone but the owner is kept from breaking and placing blocks inside a claimed chunk")
            .define("territoryProtectBlocks", true);

    public static final ModConfigSpec.BooleanValue TERRITORY_PROTECT_INTERACTIONS = BUILDER
            .comment("Whether anyone but the owner is kept from opening chests, doors, gates, buttons and levers inside a claimed chunk")
            .define("territoryProtectInteractions", true);

    public static final ModConfigSpec.BooleanValue TERRITORY_PROTECT_FROM_EXPLOSIONS = BUILDER
            .comment("Whether explosions leave the blocks of a claimed chunk standing, unless the owner set them off")
            .define("territoryProtectFromExplosions", true);

    public static final ModConfigSpec.BooleanValue TERRITORY_OPS_BYPASS = BUILDER
            .comment("Whether operators (permission level 2 and up) build, open and unclaim inside anyone's territory as if it were their own")
            .define("territoryOpsBypass", true);

    public static final ModConfigSpec.IntValue TERRITORY_MAP_RADIUS = BUILDER
            .comment("How many chunks in each direction the territory screen's map reaches around the player. Every chunk shown is rendered by the server on each refresh, so a wide map is more work per player with the screen open")
            .defineInRange("territoryMapRadius", 4, 1, 6);

    public static final ModConfigSpec.BooleanValue TERRITORY_NOTIFY_SOUND = BUILDER
            .comment("Whether a territory notification (a claim taken, a capture started) is played as a sound as well as written in chat")
            .define("territoryNotifySound", true);

    public static final ModConfigSpec.BooleanValue TERRITORY_SHOW_BORDERS = BUILDER
            .comment("Whether the edge of every claim near a player is drawn into the world as a wall of coloured dust: green for their own land, red for somebody else's, orange for land being captured")
            .define("territoryShowBorders", true);

    public static final ModConfigSpec.IntValue TERRITORY_BORDER_DISTANCE = BUILDER
            .comment("How far from a player, in blocks, claim borders are drawn. The game drops ordinary particles further than 32 blocks off, so that is as far as this goes; every block of border in reach is a particle sent to the player twice a second")
            .defineInRange("territoryBorderDistance", 24, 4, 32);
    static {
        BUILDER.pop();
    }

    // Its own page in the config screen: the Last Prism and the Terraria bosses.
    static {
        BUILDER.comment("The Last Prism and the Terraria bosses: how hard the prism hits, where the bosses' lairs may be put, how long a beaten boss sleeps, and whether their waypoints are drawn. Switched on and off on the Features page.").push("terraria");
    }

    public static final ModConfigSpec.DoubleValue LAST_PRISM_DAMAGE = BUILDER
            .comment("How much damage the Last Prism's merged beam does per pulse (two pulses a second). The six beams before they merge each do between a tenth and a quarter of this")
            .defineInRange("lastPrismDamage", 14.0, 0.0, 200.0);

    public static final ModConfigSpec.BooleanValue LAST_PRISM_HUNGER = BUILDER
            .comment("Whether holding the Last Prism's beam makes the player hungry, standing in for the mana it costs in Terraria")
            .define("lastPrismHunger", true);

    public static final ModConfigSpec.IntValue TERRARIA_MAX_DISTANCE = BUILDER
            .comment("How far from world spawn, in blocks, a boss's lair may be put. Every block of every lair is inside this distance. Only read when lairs are placed: the first time the server runs with bosses on, or after /terraria relocate")
            .defineInRange("terrariaMaxDistance", 2000, 500, 2000);

    public static final ModConfigSpec.IntValue TERRARIA_RESPAWN_MINUTES = BUILDER
            .comment("How many real minutes a beaten boss sleeps before walking into its lair wakes it again")
            .defineInRange("terrariaRespawnMinutes", 20, 0, 10080);

    public static final ModConfigSpec.DoubleValue TERRARIA_BOSS_HEALTH = BUILDER
            .comment("How much health the bosses have, as a multiple of their own. Each player fighting beyond the first adds 60% of their base health on top of this")
            .defineInRange("terrariaBossHealth", 1.0, 0.1, 10.0);

    public static final ModConfigSpec.DoubleValue TERRARIA_BOSS_DAMAGE = BUILDER
            .comment("How hard the bosses and their shots hit, as a multiple of their own. Each player fighting beyond the first adds 15% on top of this")
            .defineInRange("terrariaBossDamage", 1.0, 0.1, 10.0);

    public static final ModConfigSpec.BooleanValue TERRARIA_SHOW_WAYPOINTS = BUILDER
            .comment("Whether each boss's lair is marked on the HUD with a waypoint, its name and how far off it is. Read on the client")
            .define("terrariaShowWaypoints", true);
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
