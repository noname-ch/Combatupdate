package ch.bbcag.combatupdate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

import ch.bbcag.combatupdate.entity.Exobeam;

// The Exoblade, after the Calamity mod's endgame Terraria sword. Two things set it apart from a
// netherite sword with bigger numbers:
//
// A full-strength swing throws an Exobeam (see Exobeam), whether or not it connects with anything,
// so the blade fights at range as well as up close. Half-charged swings throw nothing: the beam is
// the reward for timing the meter, the same bargain the sword itself strikes.
//
// Right-click lunges along the look direction. The first thing the lunge runs into takes a heavy
// hit and bounces the player back off it, with a moment of invulnerability to cover the retreat -
// Terraria's in-and-out dash, and a gap-closer that does not leave you standing in the crowd you
// just cut into.
public final class Exoblade {
    // Netherite swords pass 3.0 here and land at 8 damage; 9.0 puts this at 14.
    private static final float ATTACK_DAMAGE_BASELINE = 9.0F;

    // Netherite swords pass -2.4 for 1.6 swings a second; -2.0 is 2.0 a second. Faster than any
    // vanilla sword, but still a meter to time, which the beam depends on.
    private static final float ATTACK_SPEED_BASELINE = -2.0F;

    // How full the strength meter has to be for a swing to throw a beam. Not quite 1.0, because the
    // meter is read a fraction of a tick after the click and a well-timed swing can fall just short.
    private static final float FULL_SWING = 0.9F;

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

    // Players who are lunging right now. Kept per side for the reason ElytraBoost gives: in single
    // player the client and server player share a UUID in the one JVM.
    private static final Map<UUID, Dash> CLIENT_DASHES = new ConcurrentHashMap<>();
    private static final Map<UUID, Dash> SERVER_DASHES = new ConcurrentHashMap<>();

    private static final class Dash {
        private final Vec3 direction;
        private int ticksLeft = DASH_TICKS;

        private Dash(Vec3 direction) {
            this.direction = direction;
        }
    }

    private Exoblade() {
    }

    public static Item.Properties properties(Item.Properties properties) {
        return properties.sword(ToolMaterial.NETHERITE, ATTACK_DAMAGE_BASELINE, ATTACK_SPEED_BASELINE)
                .fireResistant()
                .rarity(Rarity.EPIC);
    }

    // A swing at the air or at a block. Called from ExobladeSwingMixin, which is the one place the
    // server hears about a left click that hit no entity - and it is heard there before the strength
    // meter is reset for the swing, so the meter still says how well it was timed.
    public static void onSwing(ServerPlayer player) {
        if (player.getAttackStrengthScale(0.5F) >= FULL_SWING) {
            throwBeam(player);
        }
    }

    // A swing that connects. The server resets the meter at the end of the attack, before the swing
    // packet that follows it arrives, so onSwing sees an empty meter for these and this is the one
    // that throws the beam. Never both, then.
    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && player.getAttackStrengthScale(0.5F) >= FULL_SWING) {
            throwBeam(player);
        }
    }

    private static void throwBeam(ServerPlayer player) {
        if (!Config.on(Config.ENABLE_EXOBLADE) || !player.getMainHandItem().is(CombatUpdate.EXOBLADE.get())) {
            return;
        }

        ServerLevel level = player.level();
        Vec3 look = player.getLookAngle();
        Vec3 from = player.getEyePosition().add(look.scale(0.5)).subtract(0.0, 0.2, 0.0);
        Exobeam beam = new Exobeam(level, player, look);
        beam.setPos(from.x, from.y, from.z);
        level.addFreshEntity(beam);

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F, 1.6F);
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
        return true;
    }

    // Carries a lunge along for its length, and ends it early on the first thing it runs into.
    //
    // Both sides check for that hit, for the same reason both sides move the player: the client
    // owns its own movement, so it has to know to bounce without waiting for the server to say so.
    // Only the server deals the damage.
    public static void tick(Player player) {
        Level level = player.level();
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

        Vec3 center = target.getBoundingBox().getCenter();
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, center.x, center.y, center.z, 3, 0.4, 0.3, 0.4, 0.0);
        level.sendParticles(ParticleTypes.END_ROD, center.x, center.y, center.z, 16, 0.3, 0.4, 0.3, 0.2);
        level.playSound(null, center.x, center.y, center.z,
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.2F, 0.8F);
    }
}
