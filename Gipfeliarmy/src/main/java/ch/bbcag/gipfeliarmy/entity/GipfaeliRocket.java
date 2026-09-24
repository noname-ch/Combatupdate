package ch.bbcag.gipfeliarmy.entity;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.gipfeliarmy.GipfeliArmyMod;
import ch.bbcag.gipfeliarmy.Config;

// A Gipfaeli fired out of the launcher: constant speed, explodes on impact, and - if the launcher had
// something sighted when it went off - bends towards whatever that was.
//
// The homing is a capped turn rather than a heading set straight onto the target, so the rocket has a
// turning circle. Something that breaks hard across its path can still out-turn it, which is what
// keeps a lock from being a guaranteed hit; how tight that circle is comes off gipfaeliTurnRate.
public final class GipfaeliRocket extends Fireball {
    // Below this a direction is too short to normalize into a heading.
    private static final double EPSILON = 1.0E-6;

    // The entity it was locked on to at launch, held as a UUID so it survives being written out and
    // read back rather than pinning the target entity itself in memory.
    private @Nullable UUID targetId;

    public GipfaeliRocket(EntityType<? extends GipfaeliRocket> type, Level level) {
        super(type, level);
    }

    public GipfaeliRocket(Level level, LivingEntity shooter, Vec3 direction, @Nullable LivingEntity target) {
        super(GipfeliArmyMod.GIPFAELI_ROCKET.get(), shooter, direction, level);
        // The super constructor gives it a slow, accelerating initial velocity; override it with a
        // fixed one, and leave the acceleration off so steering is the only thing changing its course.
        this.accelerationPower = 0.0;
        this.setDeltaMovement(direction.normalize().scale(Config.GIPFAELI_SPEED.getAsDouble()));
        this.setItem(new ItemStack(GipfeliArmyMod.GIPFAELI.get()));
        this.targetId = target == null ? null : target.getUUID();
    }

    @Override
    public void tick() {
        steerTowardsTarget();
        super.tick();
    }

    @Override
    protected float getInertia() {
        // Vanilla hurting projectiles lose 5% speed per tick unless their acceleration cancels it out.
        // Returning 1.0 keeps the speed constant, so a long-range shot arrives as fast as it left.
        return 1.0F;
    }

    @Override
    protected boolean shouldBurn() {
        // It is a pastry, not a fire charge.
        return false;
    }

    // Bends the current heading towards the locked target by at most one tick's worth of turn, leaving
    // the speed alone.
    private void steerTowardsTarget() {
        double turnRate = Config.GIPFAELI_TURN_RATE.getAsDouble();
        if (turnRate <= 0.0 || !(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        LivingEntity target = resolveTarget(serverLevel);
        if (target == null) {
            return;
        }

        Vec3 velocity = this.getDeltaMovement();
        double speed = velocity.length();
        if (speed < EPSILON) {
            return;
        }

        // Aimed at the middle of the target rather than its feet, so a shot at a player does not dive
        // into the ground just short of them.
        Vec3 toTarget = target.getBoundingBox().getCenter().subtract(this.position());
        double distance = toTarget.length();
        if (distance < EPSILON) {
            return;
        }

        Vec3 heading = velocity.scale(1.0 / speed);
        Vec3 desired = toTarget.scale(1.0 / distance);

        if (Config.GIPFAELI_AVOID_BLOCKS.get()) {
            // Never further than the target itself: past that, anything solid is behind whatever we
            // are aiming at and is none of our business.
            desired = steerClearOf(desired, Math.min(distance, Config.GIPFAELI_AVOID_LOOKAHEAD.getAsDouble()));
        }

        double maxTurn = Math.toRadians(turnRate);
        double angle = Math.acos(Math.clamp(heading.dot(desired), -1.0, 1.0));
        Vec3 steered = angle <= maxTurn ? desired : turn(heading, desired, maxTurn);

        this.setDeltaMovement(steered.scale(speed));
    }

    // How far off the straight line to the target the rocket will look for a way past, nearest first.
    // Anything wider than the last of these is not a detour any more, and it flies the line instead.
    private static final double[] DETOUR_DEGREES = {15.0, 30.0, 45.0, 60.0, 75.0};

    // Picks a heading that has clear air along it, as close to the one wanted as can be found.
    //
    // Climbing is tried before turning and turning before diving, because what is usually in the way is
    // ground: going over a hill gets there, going round it takes longer, and going under it does not
    // happen. The turn rate still caps how fast the rocket can take up whatever this returns, so a
    // detour is leaned into rather than snapped to.
    private Vec3 steerClearOf(Vec3 desired, double lookahead) {
        if (isClear(desired, lookahead)) {
            return desired;
        }

        // A frame around the direction we want: one axis level with the horizon, one at right angles
        // to it. Rotating about the level one pitches; rotating about the other yaws.
        Vec3 level = desired.cross(new Vec3(0.0, 1.0, 0.0));
        if (level.lengthSqr() < EPSILON * EPSILON) {
            // Pointing straight up or down leaves no horizon to work from; any level axis will do.
            level = new Vec3(1.0, 0.0, 0.0);
        }
        level = level.normalize();
        Vec3 lift = level.cross(desired).normalize();

        for (double degrees : DETOUR_DEGREES) {
            double radians = Math.toRadians(degrees);
            Vec3[] attempts = {
                    rotateAbout(desired, level, -radians),   // climb
                    rotateAbout(desired, lift, radians),     // one way round
                    rotateAbout(desired, lift, -radians),    // the other
                    rotateAbout(desired, level, radians),    // dive, last of all
            };

            for (Vec3 attempt : attempts) {
                if (isClear(attempt, lookahead)) {
                    return attempt;
                }
            }
        }

        // Boxed in on every side: hold the line and let it hit whatever is there.
        return desired;
    }

    private boolean isClear(Vec3 direction, double distance) {
        Vec3 from = this.position();
        Vec3 to = from.add(direction.scale(distance));
        return this.level().clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
    }

    // Rodrigues' rotation: turns a vector about an arbitrary axis, which the two frames above both need.
    private static Vec3 rotateAbout(Vec3 vector, Vec3 axis, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return vector.scale(cos)
                .add(axis.cross(vector).scale(sin))
                .add(axis.scale(axis.dot(vector) * (1.0 - cos)));
    }

    // Rotates heading the given angle towards desired, in the plane the two of them span. The combat
    // mod's Exobeam and Scarlet bullet home the same way, off a copy of this (see Steering there):
    // the two mods share no code, so each keeps its own.
    static Vec3 turn(Vec3 heading, Vec3 desired, double radians) {
        // The part of the target direction at right angles to where we are pointing: the direction the
        // turn has to go in. It vanishes when the two are exactly in line, which leaves nothing to
        // turn towards and no turn to make.
        Vec3 sideways = desired.subtract(heading.scale(heading.dot(desired)));
        if (sideways.lengthSqr() < EPSILON * EPSILON) {
            return heading;
        }

        return heading.scale(Math.cos(radians))
                .add(sideways.normalize().scale(Math.sin(radians)));
    }

    private @Nullable LivingEntity resolveTarget(ServerLevel level) {
        if (this.targetId == null) {
            return null;
        }

        return level.getEntity(this.targetId) instanceof LivingEntity target && target.isAlive()
                ? target
                : null;
    }

    @Override
    protected void onHit(HitResult hitResult) {
        super.onHit(hitResult);
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        double power = Config.GIPFAELI_EXPLOSION_POWER.getAsDouble();
        if (power > 0.0) {
            // The boolean is fire, not griefing. What breaks blocks is the interaction: MOB weighs
            // mobGriefing and the owner's say in it, NONE leaves the terrain standing whatever the
            // game rules say.
            serverLevel.explode(this, this.getX(), this.getY(), this.getZ(), (float) power, false,
                    Config.GIPFAELI_BREAKS_BLOCKS.get()
                            ? Level.ExplosionInteraction.MOB
                            : Level.ExplosionInteraction.NONE);
        }

        this.discard();
    }

    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        super.onHitEntity(hitResult);
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        float damage = (float) Config.GIPFAELI_DAMAGE.getAsDouble();
        if (damage <= 0.0F) {
            return;
        }

        Entity target = hitResult.getEntity();
        DamageSource damageSource = this.damageSources().fireball(this, this.getOwner());
        target.hurtServer(serverLevel, damageSource, damage);
        EnchantmentHelper.doPostAttackEffects(serverLevel, target, damageSource);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (this.targetId != null) {
            output.store("LockTarget", UUIDUtil.CODEC, this.targetId);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.targetId = input.read("LockTarget", UUIDUtil.CODEC).orElse(null);
    }
}
