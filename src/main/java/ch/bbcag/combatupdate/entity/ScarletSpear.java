package ch.bbcag.combatupdate.entity;

import java.util.HashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.Config;

// The spear the Scarlet Devil throws: a streak of red light, much faster than a trident and dead
// straight, that runs through everything in its way.
//
// Each creature it runs through, and whatever wall finally stops it, gets a Scarlet Blast: a burst that
// hurts everything around it except the thrower and their allies, and never breaks a block. On the way
// it sheds bullets (see ScarletBullet) to either side, which hang in the air a moment and then go after
// whatever monster is nearest - so a throw down a corridor leaves the corridor's side rooms cleared too.
//
// A Gungnir, the fully charged throw, is the same spear bigger: double damage, a blast twice as wide,
// bullets three times as thick, and a heal for its thrower from every creature it strikes.
public final class ScarletSpear extends AbstractHurtingProjectile {
    // The one red the spear, its bullets and its blasts are all drawn in.
    public static final int SCARLET = 0xE0142E;

    private static final EntityDataAccessor<Boolean> DATA_GUNGNIR =
            SynchedEntityData.defineId(ScarletSpear.class, EntityDataSerializers.BOOLEAN);

    // How long it flies before it gives out, in ticks.
    private static final int LIFETIME = 30;

    // How often it sheds a bullet, in ticks: every third one for a plain throw, every one for a Gungnir.
    private static final int BULLET_INTERVAL = 3;
    private static final int GUNGNIR_BULLET_INTERVAL = 1;

    // What a Gungnir multiplies: the spear's own damage, the blast's reach and the spear's size.
    private static final float GUNGNIR_DAMAGE_SCALE = 2.0F;
    private static final double GUNGNIR_BLAST_SCALE = 2.0;
    private static final float GUNGNIR_SIZE_SCALE = 2.0F;

    // Below this a direction is too short to normalize into a heading.
    private static final double EPSILON = 1.0E-6;

    // Everything it has already run through, so that passing on through the far side of a creature
    // does not count as hitting it again. Not saved: a spear lives a second and a half.
    private final Set<Integer> struck = new HashSet<>();

    public ScarletSpear(EntityType<? extends ScarletSpear> type, Level level) {
        super(type, level);
    }

    public ScarletSpear(Level level, LivingEntity owner, Vec3 direction, boolean gungnir) {
        super(CombatUpdate.SCARLET_SPEAR.get(), owner, direction, level);
        // The same override as the rocket and the Exobeam: a fixed speed, no acceleration.
        this.accelerationPower = 0.0;
        this.setDeltaMovement(direction.normalize().scale(Config.SCARLET_SPEAR_SPEED.getAsDouble()));
        this.entityData.set(DATA_GUNGNIR, gungnir);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_GUNGNIR, false);
    }

    public boolean isGungnir() {
        return this.entityData.get(DATA_GUNGNIR);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (DATA_GUNGNIR.equals(accessor)) {
            this.refreshDimensions();
        }
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        EntityDimensions dimensions = super.getDimensions(pose);
        return this.isGungnir() ? dimensions.scale(GUNGNIR_SIZE_SCALE) : dimensions;
    }

    @Override
    public void tick() {
        if (this.level() instanceof ServerLevel serverLevel) {
            if (this.tickCount > LIFETIME) {
                this.discard();
                return;
            }

            if (this.tickCount % (this.isGungnir() ? GUNGNIR_BULLET_INTERVAL : BULLET_INTERVAL) == 0) {
                shedBullet(serverLevel);
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
        // Vanilla's trail is a single particle a tick; #trail draws a better one of its own.
        return null;
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        return super.canHitEntity(entity) && !this.struck.contains(entity.getId());
    }

    // A bullet thrown off to the side, at right angles to the spear's flight and in a random direction
    // round it, so that a throw leaves a ring-shaped spray behind it rather than a line.
    private void shedBullet(ServerLevel level) {
        Vec3 velocity = this.getDeltaMovement();
        if (velocity.lengthSqr() < EPSILON) {
            return;
        }

        Vec3 heading = velocity.normalize();
        Vec3 random = new Vec3(this.random.nextGaussian(), this.random.nextGaussian(), this.random.nextGaussian());
        Vec3 sideways = random.subtract(heading.scale(random.dot(heading)));
        if (sideways.lengthSqr() < EPSILON) {
            return;
        }

        LivingEntity owner = this.getOwner() instanceof LivingEntity living ? living : null;
        ScarletBullet bullet = new ScarletBullet(level, owner, sideways.normalize());
        bullet.setPos(this.getX(), this.getY(), this.getZ());
        level.addFreshEntity(bullet);
    }

    // A thick red streak along the stretch just flown. At four blocks a tick it has to be laid down
    // densely, or it reads as a row of dots.
    private void trail() {
        Vec3 from = new Vec3(this.xo, this.yo, this.zo);
        Vec3 step = this.position().subtract(from);
        boolean gungnir = this.isGungnir();
        int points = Math.max(2, (int) Math.ceil(step.length() * 3.0));
        ParticleOptions dust = new DustParticleOptions(SCARLET, gungnir ? 2.5F : 1.4F);
        for (int i = 0; i < points; i++) {
            Vec3 at = from.add(step.scale(i / (double) points));
            this.level().addParticle(dust, at.x, at.y, at.z, 0.0, 0.0, 0.0);
        }

        if (gungnir || this.tickCount % 2 == 0) {
            this.level().addParticle(ParticleTypes.CRIMSON_SPORE, this.getX(), this.getY(), this.getZ(), 0.0, 0.0, 0.0);
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        super.onHitEntity(hitResult);
        Entity target = hitResult.getEntity();
        // Kept on both sides, since the client flies its own copy and should not stall on it either.
        this.struck.add(target.getId());
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        LivingEntity owner = this.getOwner() instanceof LivingEntity living ? living : null;
        float damage = (float) Config.SCARLET_SPEAR_DAMAGE.getAsDouble() * (this.isGungnir() ? GUNGNIR_DAMAGE_SCALE : 1.0F);
        if (damage > 0.0F) {
            DamageSource damageSource = this.damageSources().mobProjectile(this, owner);
            if (target.hurtServer(serverLevel, damageSource, damage)) {
                EnchantmentHelper.doPostAttackEffects(serverLevel, target, damageSource);
                if (this.isGungnir() && owner != null && target instanceof LivingEntity) {
                    owner.heal((float) Config.GUNGNIR_HEAL.getAsDouble());
                }
            }
        }

        blast(serverLevel, target.getBoundingBox().getCenter());
    }

    @Override
    protected void onHitBlock(BlockHitResult hitResult) {
        super.onHitBlock(hitResult);
        if (this.level() instanceof ServerLevel serverLevel) {
            blast(serverLevel, hitResult.getLocation());
            this.discard();
        }
    }

    // The Scarlet Blast. Its damage ignores the moment of invulnerability a hit leaves behind: the
    // spear has almost always just struck whatever is at the centre of it, and a blast that did
    // nothing to the one creature it was aimed at would be no blast at all.
    private void blast(ServerLevel level, Vec3 at) {
        double radius = Config.SCARLET_BLAST_RADIUS.getAsDouble() * (this.isGungnir() ? GUNGNIR_BLAST_SCALE : 1.0);
        float damage = (float) Config.SCARLET_BLAST_DAMAGE.getAsDouble();
        Entity owner = this.getOwner();
        if (radius > 0.0 && damage > 0.0F) {
            DamageSource damageSource = this.damageSources().mobProjectile(this,
                    owner instanceof LivingEntity living ? living : null);
            for (LivingEntity caught : level.getEntitiesOfClass(LivingEntity.class, AABB.ofSize(at, radius * 2, radius * 2, radius * 2),
                    candidate -> candidate.isAlive() && candidate != owner && !candidate.isSpectator()
                            && !(candidate instanceof Player player && player.isCreative())
                            && (owner == null || !owner.isAlliedTo(candidate))
                            && candidate.getBoundingBox().distanceToSqr(at) <= radius * radius)) {
                caught.setInvulnerableTime(0);
                caught.hurtServer(level, damageSource, damage);
            }
        }

        int count = (int) Math.max(12, radius * radius * 6);
        double spread = Math.max(0.2, radius * 0.35);
        level.sendParticles(new DustParticleOptions(SCARLET, 2.0F), at.x, at.y, at.z, count, spread, spread, spread, 0.0);
        level.sendParticles(ParticleTypes.CRIMSON_SPORE, at.x, at.y, at.z, count / 2, spread, spread, spread, 0.05);
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS,
                this.isGungnir() ? 1.2F : 0.7F, this.isGungnir() ? 1.0F : 1.5F);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putBoolean("gungnir", this.isGungnir());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.entityData.set(DATA_GUNGNIR, input.getBooleanOr("gungnir", false));
    }
}
