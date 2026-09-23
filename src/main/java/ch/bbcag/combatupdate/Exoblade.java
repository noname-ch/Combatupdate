package ch.bbcag.combatupdate;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

import ch.bbcag.combatupdate.entity.Exobeam;

// The Exoblade, after the Calamity mod's endgame Terraria sword, and its moves there:
//
// It is a big blade: it reaches further than any other sword, every full-strength swing cuts
// everything in the arc in front of the player and not just what was aimed at, and holding the attack
// key keeps it swinging (see ExobladeAutoSwing), as Terraria's auto-reuse swords do.
//
// A full-strength swing throws an Exobeam (see Exobeam), whether or not it connects with anything,
// so the blade fights at range as well as up close. Half-charged swings throw nothing: the beam is
// the reward for timing the meter, the same bargain the sword itself strikes. Whatever a beam hits is
// then cut by a quick flurry of Exo slashes (see #startSlashes).
//
// Right-click lunges along the look direction. The first thing the lunge runs into takes a heavy
// hit and bounces the player back off it, with a moment of invulnerability to cover the retreat -
// Terraria's in-and-out dash, and a gap-closer that does not leave you standing in the crowd you
// just cut into.
//
// A lunge that lands leaves the blade charged for a moment, and the next swing inside that moment is
// the big slash: a huge arc that cuts everything in front of the player and throws a fan of beams.
// So the rhythm the blade rewards is Calamity's: lunge in, bounce out, swing.
public final class Exoblade {
    // Netherite swords pass 3.0 here and land at 8 damage; 9.0 puts this at 14.
    private static final float ATTACK_DAMAGE_BASELINE = 9.0F;

    // Netherite swords pass -2.4 for 1.6 swings a second; -2.0 is 2.0 a second. Faster than any
    // vanilla sword, but still a meter to time, which the beam depends on.
    private static final float ATTACK_SPEED_BASELINE = -2.0F;

    // How full the strength meter has to be for a swing to throw a beam. Not quite 1.0, because the
    // meter is read a fraction of a tick after the click and a well-timed swing can fall just short.
    private static final float FULL_SWING = 0.9F;

    // How far it reaches, in blocks: a sword reaches 3, and creative players 5.
    private static final float REACH = 4.5F;
    private static final float CREATIVE_REACH = 6.5F;

    // How wide an arc an ordinary full-strength swing cuts either side of the look direction.
    private static final double SWING_CUT_HALF_ARC = Math.toRadians(60.0);

    // How long a lunge lasts, in ticks.
    private static final int DASH_TICKS = 6;

    // How far past the player's own box the lunge reaches for something to hit.
    private static final double DASH_REACH = 0.75;

    // How hard the player comes back off whatever the lunge hit, and how long they are untouchable
    // for afterwards: long enough to clear the counter-swing, not long enough to stand and trade.
    private static final double BOUNCE_SPEED = 0.9;
    private static final double BOUNCE_LIFT = 0.35;
    private static final int BOUNCE_INVULNERABLE_TICKS = 10;

    // What a lunge that ran out without hitting anything is left carrying: enough to land where it
    // was going rather than stopping dead in the air.
    private static final double DASH_CARRY = 0.5;

    // The Exo slashes a beam leaves on what it hits: how many, and how many ticks apart.
    private static final int SLASH_COUNT = 3;
    private static final int SLASH_INTERVAL = 2;

    // The big slash: how far it reaches, how wide an arc it cuts either side of the look direction,
    // and how far apart, in degrees, the beams it throws fan out.
    private static final double BIG_SLASH_REACH = 5.0;
    private static final double BIG_SLASH_HALF_ARC = Math.toRadians(80.0);
    private static final float BIG_SLASH_BEAM_SPREAD = 15.0F;

    // How far in front of the eyes, and how far below them, a swing's arc is drawn, and how wide.
    private static final double SWING_DROP = 0.3;
    private static final double SWING_RADIUS = 3.0;
    private static final double SWING_HALF_ARC = Math.toRadians(70.0);

    // Players who are lunging right now. Kept per side for the reason ElytraBoost gives: in single
    // player the client and server player share a UUID in the one JVM.
    private static final Map<UUID, Dash> CLIENT_DASHES = new ConcurrentHashMap<>();
    private static final Map<UUID, Dash> SERVER_DASHES = new ConcurrentHashMap<>();

    // Server only, both of these: the game time a player's big slash stays ready until, and which way
    // their last swing went, so that the next one comes back the other way.
    private static final Map<UUID, Long> BIG_SLASH_READY_UNTIL = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> BACKHAND = new ConcurrentHashMap<>();

    // Exo slashes still to come. Touched only on the server thread.
    private static final List<Slashes> SLASHES = new ArrayList<>();

    private static final class Dash {
        private final Vec3 direction;
        private int ticksLeft = DASH_TICKS;

        private Dash(Vec3 direction) {
            this.direction = direction;
        }
    }

    private static final class Slashes {
        private final ServerLevel level;
        private final @Nullable LivingEntity owner;
        private final LivingEntity target;
        private int left = SLASH_COUNT;
        private int wait = 0;

        private Slashes(ServerLevel level, @Nullable LivingEntity owner, LivingEntity target) {
            this.level = level;
            this.owner = owner;
            this.target = target;
        }
    }

    private Exoblade() {
    }

    public static Item.Properties properties(Item.Properties properties) {
        return properties.sword(ToolMaterial.NETHERITE, ATTACK_DAMAGE_BASELINE, ATTACK_SPEED_BASELINE)
                .component(DataComponents.ATTACK_RANGE, new AttackRange(0.0F, REACH, 0.0F, CREATIVE_REACH, 0.3F, 1.0F))
                .fireResistant()
                .rarity(Rarity.EPIC);
    }

    // A swing at the air or at a block. Called from ExobladeSwingMixin, which is the one place the
    // server hears about a left click that hit no entity - and it is heard there before the strength
    // meter is reset for the swing, so the meter still says how well it was timed.
    public static void onSwing(ServerPlayer player) {
        if (!bigSlash(player) && player.getAttackStrengthScale(0.5F) >= FULL_SWING) {
            fullSwing(player, null);
        }
    }

    // A swing that connects. The server resets the meter at the end of the attack, before the swing
    // packet that follows it arrives, so onSwing sees an empty meter for these and this is the one
    // that throws the beam. Never both, then - and the big slash, spent by whichever comes first,
    // never goes off twice either.
    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && !bigSlash(player)
                && player.getAttackStrengthScale(0.5F) >= FULL_SWING) {
            fullSwing(player, event.getTarget());
        }
    }

    private static boolean wielding(Player player) {
        return Config.on(Config.ENABLE_EXOBLADE) && player.getMainHandItem().is(CombatUpdate.EXOBLADE.get());
    }

    // A full-strength swing: a beam thrown, and everything in the arc cut. Whatever the swing was
    // aimed at is left out of the cut, since the swing itself is already hitting it.
    private static void fullSwing(ServerPlayer player, @Nullable Entity struck) {
        if (!wielding(player)) {
            return;
        }

        ServerLevel level = player.level();
        launchBeam(level, player, player.getLookAngle());
        swingArc(level, player, nextBackhand(player), SWING_RADIUS, 14, 5);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F, 1.6F);

        float damage = (float) Config.EXOBLADE_SWING_CUT_DAMAGE.getAsDouble();
        if (damage > 0.0F) {
            DamageSource source = level.damageSources().playerAttack(player);
            for (LivingEntity caught : inArc(player, REACH, SWING_CUT_HALF_ARC)) {
                if (caught != struck && caught.hurtServer(level, source, damage)) {
                    Vec3 look = player.getLookAngle();
                    caught.knockback(0.4, -look.x, -look.z, source, damage);
                }
            }
        }
    }

    // Monsters and players within reach of the eyes and inside the given half-angle of where the
    // player is looking.
    private static List<LivingEntity> inArc(ServerPlayer player, double reach, double halfArc) {
        Vec3 look = player.getLookAngle();
        Vec3 eye = player.getEyePosition();
        double minDot = Math.cos(halfArc);
        List<LivingEntity> caught = new ArrayList<>();
        for (LivingEntity candidate : player.level().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(reach), candidate -> isQuarry(player, candidate))) {
            Vec3 toCandidate = candidate.getBoundingBox().getCenter().subtract(eye);
            if (toCandidate.lengthSqr() <= reach * reach && toCandidate.normalize().dot(look) >= minDot) {
                caught.add(candidate);
            }
        }

        return caught;
    }

    private static void launchBeam(ServerLevel level, ServerPlayer player, Vec3 direction) {
        Vec3 from = player.getEyePosition().add(direction.scale(0.5)).subtract(0.0, 0.2, 0.0);
        Exobeam beam = new Exobeam(level, player, direction);
        beam.setPos(from.x, from.y, from.z);
        level.addFreshEntity(beam);
    }

    private static boolean nextBackhand(ServerPlayer player) {
        return BACKHAND.merge(player.getUUID(), true, (was, ignored) -> !was);
    }

    // A rainbow arc across the front of the player, tilted one way and then the other on alternate
    // swings, and swept the way the blade would be going: forehand right to left, backhand back again.
    private static void swingArc(ServerLevel level, ServerPlayer player, boolean backhand, double radius,
            int points, int duration) {
        Vec3 look = player.getLookAngle();
        Vec3 right = ParticleStreaks.perpendicular(look);
        Vec3 up = right.cross(look).normalize();
        double tilt = Math.toRadians(backhand ? -25.0 : 25.0);
        Vec3 side = right.scale(Math.cos(tilt)).add(up.scale(Math.sin(tilt)));
        Vec3 center = player.getEyePosition().subtract(0.0, SWING_DROP, 0.0);
        double hue = level.getGameTime() * 0.05;
        double start = backhand ? -SWING_HALF_ARC : SWING_HALF_ARC;
        ParticleStreaks.arc(level, center, look, side, radius, start, -start, points, duration,
                t -> ParticleStreaks.rainbow(hue + t * 0.6));
    }

    // The big slash, if the player has one ready: spends it and cuts everything in front of them.
    private static boolean bigSlash(ServerPlayer player) {
        if (!wielding(player)) {
            return false;
        }

        Long readyUntil = BIG_SLASH_READY_UNTIL.remove(player.getUUID());
        ServerLevel level = player.level();
        if (readyUntil == null || level.getGameTime() > readyUntil) {
            return false;
        }

        Vec3 look = player.getLookAngle();
        Vec3 eye = player.getEyePosition();
        float damage = (float) Config.EXOBLADE_BIG_SLASH_DAMAGE.getAsDouble();
        if (damage > 0.0F) {
            DamageSource source = level.damageSources().playerAttack(player);
            for (LivingEntity caught : inArc(player, BIG_SLASH_REACH, BIG_SLASH_HALF_ARC)) {
                caught.setInvulnerableTime(0);
                if (caught.hurtServer(level, source, damage)) {
                    caught.knockback(1.2, -look.x, -look.z, source, damage);
                    EnchantmentHelper.doPostAttackEffects(level, caught, source);
                }

                Vec3 center = caught.getBoundingBox().getCenter();
                level.sendParticles(ParticleTypes.SWEEP_ATTACK, center.x, center.y, center.z, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }

        for (float yaw = -BIG_SLASH_BEAM_SPREAD; yaw <= BIG_SLASH_BEAM_SPREAD; yaw += BIG_SLASH_BEAM_SPREAD) {
            launchBeam(level, player, look.yRot(yaw * Mth.DEG_TO_RAD));
        }

        // Two arcs, one inside the other, and a flash where the blade meets the air.
        boolean backhand = nextBackhand(player);
        swingArc(level, player, backhand, BIG_SLASH_REACH, 28, 7);
        swingArc(level, player, backhand, BIG_SLASH_REACH * 0.6, 18, 6);
        Vec3 front = eye.add(look.scale(2.5));
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, ParticleStreaks.rainbow(level.getGameTime() * 0.05)),
                front.x, front.y, front.z, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.END_ROD, front.x, front.y, front.z, 30, 1.2, 0.6, 1.2, 0.15);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.5F, 0.6F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.TRIDENT_RIPTIDE_3, SoundSource.PLAYERS, 1.0F, 1.4F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.5F, 0.8F);
        return true;
    }

    // Monsters and other players: what the beams go after and what the big slash cuts. Animals and
    // villagers are left alone for the reason Exobeam gives.
    public static boolean isQuarry(@Nullable Entity owner, LivingEntity candidate) {
        return candidate.isAlive() && candidate != owner && !candidate.isSpectator()
                && (candidate instanceof Enemy || candidate instanceof Player)
                && !(candidate instanceof Player player && player.isCreative())
                && (owner == null || !owner.isAlliedTo(candidate));
    }

    // Called by an Exobeam when it hits something: the Exo slashes that follow it in.
    public static void startSlashes(ServerLevel level, @Nullable LivingEntity owner, LivingEntity target) {
        if (Config.EXOBEAM_SLASH_DAMAGE.getAsDouble() > 0.0) {
            SLASHES.add(new Slashes(level, owner, target));
        }
    }

    // Deals the Exo slashes that are due. Called every server tick.
    public static void tickSlashes() {
        Iterator<Slashes> iterator = SLASHES.iterator();
        while (iterator.hasNext()) {
            Slashes slashes = iterator.next();
            if (!slashes.target.isAlive() || slashes.target.isRemoved() || slashes.target.level() != slashes.level) {
                iterator.remove();
                continue;
            }

            if (slashes.wait-- > 0) {
                continue;
            }

            slash(slashes);
            slashes.wait = SLASH_INTERVAL - 1;
            if (--slashes.left <= 0) {
                iterator.remove();
            }
        }
    }

    // One cut straight through the target at a random angle, twice as long as it is big. Like the
    // Scarlet Devil's bullets it ignores the moment of invulnerability the last hit left: there are
    // three of them, a tick or two apart, on something the beam has only just struck.
    private static void slash(Slashes slashes) {
        ServerLevel level = slashes.level;
        LivingEntity target = slashes.target;
        Vec3 center = target.getBoundingBox().getCenter();
        double length = Math.max(1.5, target.getBbWidth() + target.getBbHeight());
        Vec3 direction = new Vec3(target.getRandom().nextGaussian(), target.getRandom().nextGaussian(),
                target.getRandom().nextGaussian());
        if (direction.lengthSqr() < 1.0E-6) {
            direction = new Vec3(1.0, 0.0, 0.0);
        }

        Vec3 half = direction.normalize().scale(length / 2.0);
        double hue = target.getRandom().nextDouble();
        ParticleStreaks.line(level, center.subtract(half), center.add(half), 8, 4,
                t -> ParticleStreaks.rainbow(hue + t * 0.3));
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, center.x, center.y, center.z, 4, 0.2, 0.2, 0.2, 0.3);
        level.playSound(null, center.x, center.y, center.z,
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.5F, 1.7F + target.getRandom().nextFloat() * 0.3F);

        DamageSource source = slashes.owner instanceof Player player
                ? level.damageSources().playerAttack(player)
                : level.damageSources().magic();
        target.setInvulnerableTime(0);
        target.hurtServer(level, source, (float) Config.EXOBEAM_SLASH_DAMAGE.getAsDouble());
    }

    // Right-click: start a lunge. Runs on both sides from the same interact event, so the client
    // moves itself at once rather than waiting to be told to by the server.
    public static boolean use(Player player, ItemStack stack, Level level) {
        if (!Config.on(Config.ENABLE_EXOBLADE)
                || !stack.is(CombatUpdate.EXOBLADE.get())
                || player.getCooldowns().isOnCooldown(stack)) {
            return false;
        }

        dashes(level).put(player.getUUID(), new Dash(player.getLookAngle()));
        player.getCooldowns().addCooldown(stack, Config.EXOBLADE_DASH_COOLDOWN_TICKS.getAsInt());

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.TRIDENT_RIPTIDE_1, SoundSource.PLAYERS, 1.0F, 1.2F);
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.SMALL_GUST, player.getX(), player.getY() + 0.3, player.getZ(),
                    1, 0.0, 0.0, 0.0, 0.0);
        }

        return true;
    }

    // Carries a lunge along for its length, and ends it early on the first thing it runs into.
    //
    // Both sides check for that hit, for the same reason both sides move the player: the client
    // owns its own movement, so it has to know to bounce without waiting for the server to say so.
    // Only the server deals the damage.
    public static void tick(Player player) {
        Level level = player.level();
        if (level instanceof ServerLevel serverLevel) {
            glowIfReady(serverLevel, player);
        }

        Map<UUID, Dash> dashes = dashes(level);
        Dash dash = dashes.get(player.getUUID());
        if (dash == null) {
            return;
        }

        // Switching the feature off, or dying mid-lunge, ends it where it is.
        if (!Config.on(Config.ENABLE_EXOBLADE) || !player.isAlive()) {
            dashes.remove(player.getUUID());
            return;
        }

        if (level instanceof ServerLevel serverLevel) {
            afterimage(serverLevel, player, dash);
        }

        Vec3 velocity = dash.direction.scale(Config.EXOBLADE_DASH_SPEED.getAsDouble());
        LivingEntity struck = firstInPath(player, velocity);
        if (struck != null) {
            dashes.remove(player.getUUID());
            bounce(player, dash.direction);
            if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
                strike(serverLevel, serverPlayer, struck, dash.direction);
            }
            return;
        }

        player.setDeltaMovement(velocity);
        // A lunge straight up would otherwise count as a fall on the way back down.
        player.resetFallDistance();

        if (--dash.ticksLeft <= 0) {
            dashes.remove(player.getUUID());
            player.setDeltaMovement(velocity.scale(DASH_CARRY));
        }
    }

    private static Map<UUID, Dash> dashes(Level level) {
        return level.isClientSide() ? CLIENT_DASHES : SERVER_DASHES;
    }

    // A streak of colour along the stretch of the lunge just covered, at chest height.
    private static void afterimage(ServerLevel level, Player player, Dash dash) {
        Vec3 now = player.position().add(0.0, player.getBbHeight() * 0.55, 0.0);
        Vec3 behind = now.subtract(dash.direction.scale(Config.EXOBLADE_DASH_SPEED.getAsDouble()));
        double hue = (DASH_TICKS - dash.ticksLeft) / (double) DASH_TICKS;
        ParticleStreaks.line(level, behind, now, 5, 6, t -> ParticleStreaks.rainbow(hue + t * 0.15));
    }

    // Sparks about the blade while a big slash is waiting, so the player can see the window is open.
    private static void glowIfReady(ServerLevel level, Player player) {
        Long readyUntil = BIG_SLASH_READY_UNTIL.get(player.getUUID());
        if (readyUntil == null) {
            return;
        }

        if (level.getGameTime() > readyUntil) {
            BIG_SLASH_READY_UNTIL.remove(player.getUUID());
            return;
        }

        if (level.getGameTime() % 2 == 0) {
            Vec3 look = player.getLookAngle();
            Vec3 hand = player.getEyePosition().add(look.scale(0.6))
                    .add(ParticleStreaks.perpendicular(look).scale(0.4)).subtract(0.0, 0.5, 0.0);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, hand.x, hand.y, hand.z, 2, 0.15, 0.3, 0.15, 0.02);
            level.sendParticles(ParticleTypes.END_ROD, hand.x, hand.y, hand.z, 1, 0.2, 0.3, 0.2, 0.01);
        }
    }

    private static LivingEntity firstInPath(Player player, Vec3 velocity) {
        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : player.level().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().expandTowards(velocity).inflate(DASH_REACH),
                candidate -> candidate != player && candidate.isAlive() && candidate.isPickable()
                        && !candidate.isSpectator() && !player.isAlliedTo(candidate))) {
            double distance = candidate.distanceToSqr(player);
            if (distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

    private static void bounce(Player player, Vec3 direction) {
        player.setDeltaMovement(direction.scale(-BOUNCE_SPEED).add(0.0, BOUNCE_LIFT, 0.0));
        player.resetFallDistance();
        if (player instanceof ServerPlayer) {
            // Sent down as well, in case the client's own check missed the hit it was just dealt.
            player.needsSync = true;
            player.setInvulnerableTime(BOUNCE_INVULNERABLE_TICKS);
        }
    }

    private static void strike(ServerLevel level, ServerPlayer player, LivingEntity target, Vec3 direction) {
        float damage = (float) Config.EXOBLADE_DASH_DAMAGE.getAsDouble();
        if (damage > 0.0F) {
            DamageSource source = level.damageSources().playerAttack(player);
            if (target.hurtServer(level, source, damage)) {
                target.knockback(1.0, -direction.x, -direction.z, source, damage);
                EnchantmentHelper.doPostAttackEffects(level, target, source);
            }
        }

        // The lunge landed, so the next swing is the big one.
        int window = Config.EXOBLADE_BIG_SLASH_WINDOW_TICKS.getAsInt();
        if (window > 0) {
            BIG_SLASH_READY_UNTIL.put(player.getUUID(), level.getGameTime() + window);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.5F, 1.5F);
        }

        Vec3 center = target.getBoundingBox().getCenter();
        Vec3 across = ParticleStreaks.perpendicular(direction).scale(1.4);
        double hue = level.getGameTime() * 0.05;
        ParticleStreaks.line(level, center.subtract(across).add(0.0, 0.8, 0.0), center.add(across).subtract(0.0, 0.8, 0.0),
                10, 4, t -> ParticleStreaks.rainbow(hue + t * 0.5));
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, ParticleStreaks.rainbow(hue)),
                center.x, center.y, center.z, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, center.x, center.y, center.z, 3, 0.4, 0.3, 0.4, 0.0);
        level.sendParticles(ParticleTypes.END_ROD, center.x, center.y, center.z, 16, 0.3, 0.4, 0.3, 0.2);
        level.playSound(null, center.x, center.y, center.z,
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.2F, 0.8F);
    }
}
