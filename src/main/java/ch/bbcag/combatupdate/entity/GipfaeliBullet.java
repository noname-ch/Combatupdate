package ch.bbcag.combatupdate.entity;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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

    // How far up a target the head is taken to start, as a share of its eye height. Measured off the
    // eyes rather than off a fixed number of blocks so it lands on the head of a chicken and of a
    // player alike, instead of being a rule written for one of them.
    private static final double HEAD_FRACTION = 0.88;

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

        // Where on the body the round landed is the difference between a wound and an end to it, and
        // it is the one thing that makes aiming with these worth doing: a marksman's shot that finds
        // a head is a different shot from the same round through a shoulder.
        boolean head = headshot(target, hitResult.getLocation());
        float dealt = head ? this.damage * (float) Config.WEAPON_HEADSHOT.getAsDouble() : this.damage;
        target.hurtServer(serverLevel, damageSource, dealt);
        EnchantmentHelper.doPostAttackEffects(serverLevel, target, damageSource);

        if (head) {
            announceHeadshot(serverLevel, hitResult.getLocation());
        }
    }

    // The head is the top slice of the target, taken from its eyes up. Only living things have one;
    // a round into a boat or a minecart is a round into a boat.
    private static boolean headshot(Entity target, Vec3 impact) {
        if (!(target instanceof LivingEntity) || Config.WEAPON_HEADSHOT.getAsDouble() <= 1.0) {
            return false;
        }

        return impact.y >= target.getY() + target.getEyeHeight() * HEAD_FRACTION;
    }

    // The ding an arrow gives on a player, borrowed for the same job: the shooter is usually too far
    // off to read a health bar, and this is how they learn the shot was worth taking.
    private static void announceHeadshot(ServerLevel level, Vec3 at) {
        level.playSound(null, at.x, at.y, at.z, SoundEvents.ARROW_HIT_PLAYER, SoundSource.PLAYERS, 1.0F, 1.4F);
        level.sendParticles(ParticleTypes.CRIT, true, false, at.x, at.y, at.z, 12, 0.15, 0.15, 0.15, 0.25);
    }

    @Override
    protected void onHit(HitResult hitResult) {
        super.onHit(hitResult);
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        // Something to show for a miss. Without it a round that goes wide simply stops existing, and
        // a gun you cannot see landing is a gun you cannot correct your aim with.
        if (hitResult.getType() == HitResult.Type.BLOCK) {
            Vec3 at = hitResult.getLocation();
            serverLevel.sendParticles(ParticleTypes.SMOKE, true, false, at.x, at.y, at.z, 4, 0.05, 0.05, 0.05, 0.02);
            serverLevel.playSound(null, at.x, at.y, at.z, SoundEvents.STONE_HIT, SoundSource.BLOCKS, 0.9F, 1.6F);
        }

        this.discard();
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
