package ch.bbcag.combatupdate.entity;

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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.Config;

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
        super(CombatUpdate.GIPFAELI_ROCKET.get(), shooter, direction, level);
        // The super constructor gives it a slow, accelerating initial velocity; override it with a
        // fixed one, and leave the acceleration off so steering is the only thing changing its course.
        this.accelerationPower = 0.0;
        this.setDeltaMovement(direction.normalize().scale(Config.GIPFAELI_SPEED.getAsDouble()));
        this.setItem(new ItemStack(CombatUpdate.GIPFAELI.get()));
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

        double maxTurn = Math.toRadians(turnRate);
        double angle = Math.acos(Math.clamp(heading.dot(desired), -1.0, 1.0));
        Vec3 steered = angle <= maxTurn ? desired : turn(heading, desired, maxTurn);

        this.setDeltaMovement(steered.scale(speed));
    }

    // Rotates heading the given angle towards desired, in the plane the two of them span.
    private static Vec3 turn(Vec3 heading, Vec3 desired, double radians) {
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
            boolean grief = Config.GIPFAELI_BREAKS_BLOCKS.get()
                    && EventHooks.canEntityGrief(serverLevel, this.getOwner());
            serverLevel.explode(this, this.getX(), this.getY(), this.getZ(),
                    (float) power, grief, Level.ExplosionInteraction.MOB);
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
