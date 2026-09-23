package ch.bbcag.combatupdate.entity;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.Config;

// One of the red bullets a ScarletSpear sheds in flight. It drifts off sideways for a moment, then
// picks the nearest monster or other player it can see - in any direction, since it was thrown off at
// right angles to begin with - and bends towards it until it hits or gives out.
//
// Like the Exobeam, it leaves animals and villagers alone, and here that goes for hitting as well as
// chasing: a spray of bullets that could not be aimed would otherwise take the farm with it.
//
// It has no model. What is drawn is the trail it leaves (see #trail), so the renderer is a no-op.
public final class ScarletBullet extends AbstractHurtingProjectile {
    // How long it lives, in ticks.
    private static final int LIFETIME = 40;

    // How long it drifts before it starts to home, and how fast it goes before and after.
    private static final int HOMING_DELAY = 6;
    private static final double DRIFT_SPEED = 0.35;
    private static final double HOMING_SPEED = 1.1;

    // How far it looks for something to chase, in blocks, and the most it can turn in a tick, in
    // degrees. Much tighter a turn than the Exobeam's: it starts off pointing the wrong way.
    private static final double HOMING_RANGE = 14.0;
    private static final double TURN_DEGREES = 30.0;

    // Below this a direction is too short to normalize into a heading.
    private static final double EPSILON = 1.0E-6;

    private @Nullable UUID targetId;

    public ScarletBullet(EntityType<? extends ScarletBullet> type, Level level) {
        super(type, level);
    }

    public ScarletBullet(Level level, @Nullable LivingEntity owner, Vec3 direction) {
        super(CombatUpdate.SCARLET_BULLET.get(), level);
        this.setOwner(owner);
        this.accelerationPower = 0.0;
        this.setDeltaMovement(direction.normalize().scale(DRIFT_SPEED));
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
        return 1.0F;
    }

    @Override
    protected boolean shouldBurn() {
        return false;
    }

    @Override
    protected @Nullable ParticleOptions getTrailParticle() {
        return null;
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        return super.canHitEntity(entity) && entity instanceof LivingEntity living && isQuarry(living);
    }

    // Speeds up from its drift the moment it has something to chase, and not before: a bullet with
    // nothing to go after just hangs there and fades, which is what danmaku is supposed to look like.
    private void steer(ServerLevel level) {
        LivingEntity target = resolveTarget(level);
        if (target == null) {
            return;
        }

        Vec3 velocity = this.getDeltaMovement();
        Vec3 toTarget = target.getBoundingBox().getCenter().subtract(this.position());
        if (velocity.lengthSqr() < EPSILON * EPSILON || toTarget.lengthSqr() < EPSILON * EPSILON) {
            return;
        }

        Vec3 heading = velocity.normalize();
        Vec3 desired = toTarget.normalize();
        double maxTurn = Math.toRadians(TURN_DEGREES);
        double angle = Math.acos(Math.clamp(heading.dot(desired), -1.0, 1.0));
        Vec3 steered = angle <= maxTurn ? desired : GipfaeliRocket.turn(heading, desired, maxTurn);

        this.setDeltaMovement(steered.scale(HOMING_SPEED));
    }

    // Keeps to the target it already has while that one is still worth chasing, as the Exobeam does.
    private @Nullable LivingEntity resolveTarget(ServerLevel level) {
        if (this.targetId != null
                && level.getEntity(this.targetId) instanceof LivingEntity current
                && isQuarry(current) && canSee(current)) {
            return current;
        }

        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
                this.getBoundingBox().inflate(HOMING_RANGE), this::isQuarry)) {
            double distance = candidate.distanceToSqr(this);
            if (distance < nearestDistance && distance <= HOMING_RANGE * HOMING_RANGE && canSee(candidate)) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }

        this.targetId = nearest == null ? null : nearest.getUUID();
        return nearest;
    }

    private boolean isQuarry(LivingEntity candidate) {
        Entity owner = this.getOwner();
        return candidate.isAlive() && candidate != owner && !candidate.isSpectator()
                && (candidate instanceof Enemy || candidate instanceof Player)
                && !(candidate instanceof Player player && player.isCreative())
                && (owner == null || !owner.isAlliedTo(candidate));
    }

    private boolean canSee(LivingEntity candidate) {
        return this.level().clip(new ClipContext(this.position(), candidate.getBoundingBox().getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
    }

    private void trail() {
        this.level().addParticle(new DustParticleOptions(ScarletSpear.SCARLET, 1.0F),
                this.getX(), this.getY(), this.getZ(), 0.0, 0.0, 0.0);
        if (this.tickCount % 3 == 0) {
            this.level().addParticle(ParticleTypes.CRIMSON_SPORE, this.getX(), this.getY(), this.getZ(), 0.0, 0.0, 0.0);
        }
    }

    @Override
    protected void onHit(HitResult hitResult) {
        super.onHit(hitResult);
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(new DustParticleOptions(ScarletSpear.SCARLET, 1.2F),
                    this.getX(), this.getY(), this.getZ(), 6, 0.1, 0.1, 0.1, 0.0);
            this.discard();
        }
    }

    // Like the blast, a bullet ignores the invulnerability the last hit left behind: they come in a
    // swarm, and a swarm where only one in ten could land would not be worth shedding.
    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        super.onHitEntity(hitResult);
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        float damage = (float) Config.SCARLET_BULLET_DAMAGE.getAsDouble();
        if (damage <= 0.0F) {
            return;
        }

        Entity target = hitResult.getEntity();
        DamageSource damageSource = this.damageSources().mobProjectile(this,
                this.getOwner() instanceof LivingEntity owner ? owner : null);
        target.setInvulnerableTime(0);
        target.hurtServer(serverLevel, damageSource, damage);
    }
}
