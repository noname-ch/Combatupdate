package ch.bbcag.combatupdate.entity;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.Config;
import ch.bbcag.combatupdate.Exoblade;

// The beam a full-strength Exoblade swing throws: a streak of rainbow light that flies straight for a
// moment, then goes looking for something hostile and bends towards it.
//
// Unlike the Gipfaeli rocket, nothing is locked on at launch. A beam picks its own quarry, the nearest
// monster or other player in front of it with nothing solid in between, and picks again if that one
// dies first - which is what lets a crowd be cut down by swinging at the air in its general direction.
//
// Whatever it hits is then cut by a flurry of Exo slashes; see Exoblade#startSlashes.
//
// It has no model. What is drawn is the trail it leaves (see #trail), so the renderer is a no-op.
public final class Exobeam extends AbstractHurtingProjectile {
    // How long it flies before it gives out, in ticks.
    private static final int LIFETIME = 40;

    // How long it flies dead straight before it starts to home. Without this a beam thrown past one
    // target at another would snap round onto the nearer one before it had left the blade.
    private static final int HOMING_DELAY = 4;

    // The most it can turn in one tick, in degrees. Tighter than the rocket's default, because a beam
    // is a slash rather than a missile: it should reach something that sidestepped, not chase it round.
    private static final double TURN_DEGREES = 12.0;

    // Below this a direction is too short to normalize into a heading.
    private static final double EPSILON = 1.0E-6;

    private @Nullable UUID targetId;

    public Exobeam(EntityType<? extends Exobeam> type, Level level) {
        super(type, level);
    }

    public Exobeam(Level level, LivingEntity owner, Vec3 direction) {
        super(CombatUpdate.EXOBEAM.get(), owner, direction, level);
        // The same override as the rocket: a fixed speed, no acceleration, steering the only thing
        // that ever changes its course.
        this.accelerationPower = 0.0;
        this.setDeltaMovement(direction.normalize().scale(Config.EXOBEAM_SPEED.getAsDouble()));
    }

    @Override
    public void tick() {
        if (this.level() instanceof ServerLevel serverLevel) {
            if (this.tickCount > LIFETIME) {
                this.discard();
                return;
            }

            if (this.tickCount > HOMING_DELAY) {
                steer(serverLevel);
            }
        }

        super.tick();

        if (this.level().isClientSide()) {
            trail();
        }
    }

    @Override
    protected float getInertia() {
        return 1.0F;
    }

    @Override
    protected float getLiquidInertia() {
        // Light does not care what it is passing through.
        return 1.0F;
    }

    @Override
    protected boolean shouldBurn() {
        return false;
    }

    @Override
    protected @Nullable ParticleOptions getTrailParticle() {
        // Vanilla's trail is a single particle a tick; #trail draws a better one of its own.
        return null;
    }

    private void steer(ServerLevel level) {
        LivingEntity target = resolveTarget(level);
        if (target == null) {
            return;
        }

        Vec3 velocity = this.getDeltaMovement();
        double speed = velocity.length();
        Vec3 toTarget = target.getBoundingBox().getCenter().subtract(this.position());
        if (speed < EPSILON || toTarget.lengthSqr() < EPSILON * EPSILON) {
            return;
        }

        Vec3 heading = velocity.scale(1.0 / speed);
        Vec3 desired = toTarget.normalize();
        double maxTurn = Math.toRadians(TURN_DEGREES);
        double angle = Math.acos(Math.clamp(heading.dot(desired), -1.0, 1.0));
        Vec3 steered = angle <= maxTurn ? desired : Steering.turn(heading, desired, maxTurn);

        this.setDeltaMovement(steered.scale(speed));
    }

    // Keeps to the target it already has while that one is still worth chasing, and only goes looking
    // for another once it is not - so two monsters side by side do not have the beam flicking between
    // them every time one steps closer.
    private @Nullable LivingEntity resolveTarget(ServerLevel level) {
        if (this.targetId != null
                && level.getEntity(this.targetId) instanceof LivingEntity current
                && isQuarry(current)) {
            return current;
        }

        double range = Config.EXOBEAM_HOMING_RANGE.getAsDouble();
        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
                this.getBoundingBox().inflate(range), this::isQuarry)) {
            double distance = candidate.distanceToSqr(this);
            if (distance < nearestDistance && distance <= range * range) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }

        this.targetId = nearest == null ? null : nearest.getUUID();
        return nearest;
    }

    // Monsters and other players, in front of the beam, with a clear line to them. Animals and
    // villagers are left alone: a crowd-clearing blade that also shreds the farm is not a weapon
    // anybody swings twice.
    private boolean isQuarry(LivingEntity candidate) {
        Entity owner = this.getOwner();
        if (!candidate.isAlive() || candidate == owner || candidate.isSpectator()
                || !(candidate instanceof Enemy || candidate instanceof Player)
                || candidate instanceof Player player && player.isCreative()
                || owner != null && owner.isAlliedTo(candidate)) {
            return false;
        }

        // Behind it is out: turning round would take it back through whoever threw it.
        Vec3 toTarget = candidate.getBoundingBox().getCenter().subtract(this.position());
        if (toTarget.dot(this.getDeltaMovement()) <= 0.0) {
            return false;
        }

        return this.level().clip(new ClipContext(this.position(), candidate.getBoundingBox().getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
    }

    // A streak of colour cycling through the spectrum along the stretch just flown, round a thin white
    // core, dense enough to read as one line at speed rather than a row of dots. A beam is light, so
    // it throws off the odd spark as well.
    private void trail() {
        Vec3 from = new Vec3(this.xo, this.yo, this.zo);
        Vec3 step = this.position().subtract(from);
        int points = Math.max(4, (int) Math.ceil(step.length() * 5.0));
        for (int i = 0; i < points; i++) {
            Vec3 at = from.add(step.scale(i / (double) points));
            float hue = ((this.tickCount * points + i) * 0.02F) % 1.0F;
            int color = Mth.hsvToRgb(hue, 0.65F, 1.0F) & 0xFFFFFF;
            this.level().addParticle(new DustParticleOptions(color, 1.8F), at.x, at.y, at.z, 0.0, 0.0, 0.0);
            if (i % 2 == 0) {
                this.level().addParticle(new DustParticleOptions(0xFFFFFF, 0.7F), at.x, at.y, at.z, 0.0, 0.0, 0.0);
            }
        }

        this.level().addParticle(ParticleTypes.END_ROD, this.getX(), this.getY(), this.getZ(), 0.0, 0.0, 0.0);
        if (this.random.nextInt(3) == 0) {
            this.level().addParticle(ParticleTypes.ELECTRIC_SPARK, this.getX(), this.getY(), this.getZ(),
                    this.random.nextGaussian() * 0.1, this.random.nextGaussian() * 0.1, this.random.nextGaussian() * 0.1);
        }
    }

    @Override
    protected void onHit(HitResult hitResult) {
        super.onHit(hitResult);
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.END_ROD, this.getX(), this.getY(), this.getZ(),
                    12, 0.1, 0.1, 0.1, 0.15);
            serverLevel.sendParticles(ParticleTypes.ELECTRIC_SPARK, this.getX(), this.getY(), this.getZ(),
                    6, 0.2, 0.2, 0.2, 0.3);
            this.discard();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        super.onHitEntity(hitResult);
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        float damage = (float) Config.EXOBEAM_DAMAGE.getAsDouble();
        if (damage <= 0.0F) {
            return;
        }

        Entity target = hitResult.getEntity();
        DamageSource damageSource = this.damageSources().mobProjectile(this,
                this.getOwner() instanceof LivingEntity owner ? owner : null);
        target.hurtServer(serverLevel, damageSource, damage);
        EnchantmentHelper.doPostAttackEffects(serverLevel, target, damageSource);
        if (target instanceof LivingEntity living && living.isAlive()) {
            Exoblade.startSlashes(serverLevel, this.getOwner() instanceof LivingEntity owner ? owner : null, living);
        }
    }
}
