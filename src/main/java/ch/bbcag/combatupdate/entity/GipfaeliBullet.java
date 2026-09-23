package ch.bbcag.combatupdate.entity;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
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

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.Config;
import ch.bbcag.combatupdate.GipfaeliArmy;

// A crumb out of a Gipfaeli gun (see GipfaeliWeapon). Straight, fast, gone on the first thing it
// touches, and no blast behind it - the explosions in this mod belong to the launcher and the rig.
//
// What it costs its target and how long it stays in the air are set by the weapon that threw it
// rather than by the config, because a shotgun pellet and a marksman's round are the same entity
// with different numbers in it.
public final class GipfaeliBullet extends Fireball {
    // Long enough to cross any weapon's range; a round that has somehow outlived its own weapon's
    // reckoning still clears itself up rather than flying until the chunk unloads.
    private static final int MAX_LIFETIME_TICKS = 200;

    private float damage;
    private int lifetimeTicks = MAX_LIFETIME_TICKS;

    public GipfaeliBullet(EntityType<? extends GipfaeliBullet> type, Level level) {
        super(type, level);
    }

    public GipfaeliBullet(Level level, LivingEntity shooter, Vec3 direction, double speed, float damage, int lifetimeTicks) {
        super(CombatUpdate.GIPFAELI_BULLET.get(), shooter, direction, level);
        // The same bargain GipfaeliRocket strikes: no acceleration, no drag, so a round arrives as
        // fast as it left and a spread stays the shape it was fired in.
        this.accelerationPower = 0.0;
        this.setDeltaMovement(direction.normalize().scale(speed));
        this.setItem(new ItemStack(CombatUpdate.GIPFAELI.get()));
        this.damage = damage;
        this.lifetimeTicks = Math.min(lifetimeTicks, MAX_LIFETIME_TICKS);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide() && this.tickCount > this.lifetimeTicks) {
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
        // A tracer rather than the fireball's smoke: it is what tells the commander which way the
        // squad is shooting, from a distance where the soldiers themselves are specks.
        return ParticleTypes.CRIT;
    }

    // Rounds pass through whoever fired them, and through the rest of that side. Without this a
    // squad standing shoulder to shoulder shoots itself apart in the first volley, and the
    // commander standing in front of it with them - the shooter itself whatever the config says,
    // since a muzzle sits close enough to its own chest for that to be a matter of geometry.
    @Override
    protected boolean canHitEntity(Entity entity) {
        if (!super.canHitEntity(entity) || entity == this.getOwner()) {
            return false;
        }

        return Config.ARMY_FRIENDLY_FIRE.get() || !GipfaeliArmy.sameSide(this.getOwner(), entity);
    }

    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        super.onHitEntity(hitResult);
        if (!(this.level() instanceof ServerLevel serverLevel) || this.damage <= 0.0F) {
            return;
        }

        Entity target = hitResult.getEntity();
        DamageSource damageSource = this.damageSources().mobProjectile(this,
                this.getOwner() instanceof LivingEntity shooter ? shooter : null);
        target.hurtServer(serverLevel, damageSource, this.damage);
        EnchantmentHelper.doPostAttackEffects(serverLevel, target, damageSource);
    }

    @Override
    protected void onHit(HitResult hitResult) {
        super.onHit(hitResult);
        if (!this.level().isClientSide()) {
            this.discard();
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putFloat("Damage", this.damage);
        output.putInt("Lifetime", this.lifetimeTicks);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.damage = input.getFloatOr("Damage", 0.0F);
        this.lifetimeTicks = Math.min(input.getIntOr("Lifetime", MAX_LIFETIME_TICKS), MAX_LIFETIME_TICKS);
    }
}
