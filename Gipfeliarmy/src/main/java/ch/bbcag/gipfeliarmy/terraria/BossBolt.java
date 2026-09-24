package ch.bbcag.gipfeliarmy.terraria;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// Everything the bosses shoot: the Wall of Flesh's eye lasers, Plantera's seeds (some of them
// poisoned), her thorn balls and the spores she lets loose once enraged. One entity for all of them, told apart by the item it carries,
// which is also what it is drawn as - the way GipfaeliBullet is a flying Gipfaeli.
//
// Straight, no drag, gone on the first thing it hits - except spores, which drift after their
// target, slowly enough to be outrun. It flies through the ground, since the
// bosses that fire it do, and a boss that could only be shot back at from in the open would be a
// boss best fought from behind a wall.
public final class BossBolt extends Fireball {
    private static final int LIFETIME_TICKS = 100;
    // How hard a spore turns towards its target each tick, as a share of its speed.
    private static final double SPORE_STEER = 0.08;

    // Saved by position, so new kinds go on the end.
    public enum Kind {
        LASER, SEED, POISON_SEED, THORN_BALL, SPORE
    }

    private float damage;
    private Kind kind = Kind.LASER;

    public BossBolt(EntityType<? extends BossBolt> type, Level level) {
        super(type, level);
    }

    public BossBolt(Level level, LivingEntity shooter, Vec3 from, Vec3 direction, double speed, float damage, Kind kind) {
        super(TerrariaContent.BOSS_BOLT.get(), shooter, direction, level);
        this.accelerationPower = 0.0;
        this.setPos(from);
        this.setDeltaMovement(direction.normalize().scale(speed));
        this.damage = damage;
        this.kind = kind;
        this.setItem(new ItemStack(switch (kind) {
            case LASER -> TerrariaContent.BOSS_LASER.get();
            case SEED -> TerrariaContent.PLANTERA_SEED.get();
            case POISON_SEED, SPORE -> TerrariaContent.PLANTERA_POISON_SEED.get();
            case THORN_BALL -> TerrariaContent.PLANTERA_THORN_BALL.get();
        }));
    }

    @Override
    public void tick() {
        this.noPhysics = true;
        if (this.kind == Kind.SPORE && !this.level().isClientSide() && this.getOwner() instanceof Mob owner
                && owner.getTarget() != null) {
            Vec3 velocity = this.getDeltaMovement();
            Vec3 towards = owner.getTarget().getBoundingBox().getCenter().subtract(this.position()).normalize();
            double speed = velocity.length();
            this.setDeltaMovement(velocity.normalize().add(towards.scale(SPORE_STEER)).normalize().scale(speed));
        }
        super.tick();
        if (!this.level().isClientSide() && this.tickCount > LIFETIME_TICKS) {
            this.discard();
        }
    }

    @Override
    protected float getInertia() {
        return 1.0F;
    }

    @Override
    protected boolean shouldBurn() {
        return false;
    }

    @Override
    protected ParticleOptions getTrailParticle() {
        return this.getItem().is(TerrariaContent.BOSS_LASER.get())
                ? new DustParticleOptions(0xFF3030, 1.0F)
                : new DustParticleOptions(0x7CCB3C, 0.8F);
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        return super.canHitEntity(entity) && entity != this.getOwner() && !(entity instanceof TerrariaBoss)
                && !(entity instanceof BossBolt) && !TerrariaBoss.isMinion(entity);
    }

    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        super.onHitEntity(hitResult);
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }

        Entity target = hitResult.getEntity();
        DamageSource source = this.damageSources().mobProjectile(this,
                this.getOwner() instanceof LivingEntity shooter ? shooter : null);
        if (target.hurtServer(level, source, this.damage) && (this.kind == Kind.POISON_SEED || this.kind == Kind.SPORE)
                && target instanceof LivingEntity living) {
            living.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 0), this);
        }
    }

    @Override
    protected void onHit(HitResult hitResult) {
        // Blocks are flown through (see the class comment); only a hit on something alive counts.
        if (hitResult.getType() != HitResult.Type.ENTITY) {
            return;
        }

        super.onHit(hitResult);
        if (this.level() instanceof ServerLevel level) {
            Vec3 at = hitResult.getLocation();
            level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 6, 0.1, 0.1, 0.1, 0.1);
            this.discard();
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putFloat("Damage", this.damage);
        output.putInt("Kind", this.kind.ordinal());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.damage = input.getFloatOr("Damage", 0.0F);
        Kind[] kinds = Kind.values();
        this.kind = kinds[Math.clamp(input.getIntOr("Kind", 0), 0, kinds.length - 1)];
    }
}
