package ch.bbcag.combatupdate.entity;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// A thrown fireball that flies at a constant speed (no acceleration or drag, unlike vanilla Blaze/Ghast
// fireballs) and explodes on impact, similar to a Ghast's fireball.
public class CombatFireball extends Fireball {
    public CombatFireball(EntityType<? extends CombatFireball> type, Level level) {
        super(type, level);
    }

    public CombatFireball(Level level, LivingEntity shooter, Vec3 direction) {
        super(CombatUpdate.COMBAT_FIREBALL.get(), shooter, direction, level);
        // The super constructor gives it a slow, accelerating initial velocity; override it with a
        // fixed, fast one instead.
        this.accelerationPower = 0.0;
        this.setDeltaMovement(direction.normalize().scale(Config.FIREBALL_SPEED.getAsDouble()));
    }

    @Override
    protected float getInertia() {
        // Vanilla hurting projectiles lose 5% speed per tick unless their acceleration cancels it out.
        // Returning 1.0 here (instead of the default 0.95) keeps the speed constant since we have no acceleration.
        return 1.0F;
    }

    @Override
    protected void onHit(HitResult hitResult) {
        super.onHit(hitResult);
        if (this.level() instanceof ServerLevel serverLevel) {
            boolean grief = net.neoforged.neoforge.event.EventHooks.canEntityGrief(serverLevel, this.getOwner());
            this.level().explode(this, this.getX(), this.getY(), this.getZ(),
                    (float) Config.FIREBALL_EXPLOSION_POWER.getAsDouble(), grief, Level.ExplosionInteraction.MOB);
            this.discard();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        super.onHitEntity(hitResult);
        if (this.level() instanceof ServerLevel serverLevel) {
            Entity target = hitResult.getEntity();
            DamageSource damageSource = this.damageSources().fireball(this, this.getOwner());
            target.hurtServer(serverLevel, damageSource, 6.0F);
            EnchantmentHelper.doPostAttackEffects(serverLevel, target, damageSource);
        }
    }
}
