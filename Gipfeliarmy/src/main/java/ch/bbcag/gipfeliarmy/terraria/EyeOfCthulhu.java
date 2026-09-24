package ch.bbcag.gipfeliarmy.terraria;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

// The Eye of Cthulhu. It hangs in the air above whoever it is after, circling, and then throws
// itself at them - three times in a row, a pause, and again. At half health it spins, sheds its
// iris in a spray of blood and comes back as a mouth full of teeth: faster, harder, and charging
// four times a round instead of three.
public final class EyeOfCthulhu extends TerrariaBoss {
    private static final float CONTACT = 6.0F;
    private static final float CONTACT_ENRAGED = 9.0F;

    private static final double HOVER_HEIGHT = 7.0;
    private static final double HOVER_RADIUS = 6.0;

    private enum Stage {
        HOVER, WIND_UP, CHARGE, TRANSFORM
    }

    private Stage stage = Stage.HOVER;
    private int stageTicks;
    private int chargesLeft;
    private double circle;

    public EyeOfCthulhu(EntityType<? extends EyeOfCthulhu> type, Level level) {
        super(type, level);
        this.xpReward = 150;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 300.0)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 96.0);
    }

    @Override
    public BossKind kind() {
        return BossKind.EYE_OF_CTHULHU;
    }

    @Override
    protected float contactDamage() {
        return this.isEnraged() ? CONTACT_ENRAGED : CONTACT;
    }

    @Override
    protected void onEnrage(ServerLevel level) {
        this.stage = Stage.TRANSFORM;
        this.stageTicks = 0;
        this.playSound(SoundEvents.ENDER_DRAGON_GROWL, 3.0F, 1.4F);
    }

    @Override
    protected void bossTick(ServerLevel level, @Nullable Player target) {
        this.stageTicks++;
        boolean enraged = this.isEnraged();

        if (this.stage == Stage.TRANSFORM) {
            // Spinning in place and bleeding, while it turns into the second phase.
            this.setDeltaMovement(this.getDeltaMovement().scale(0.8));
            float yaw = this.getYRot() + 36.0F;
            this.setYRot(yaw);
            this.yBodyRot = yaw;
            this.yHeadRot = yaw;
            level.sendParticles(new DustParticleOptions(0x8A0303, 2.0F), this.getX(), this.getY() + 1.3, this.getZ(),
                    8, 0.9, 0.9, 0.9, 0.0);
            if (this.stageTicks >= 40) {
                level.sendParticles(ParticleTypes.EXPLOSION, this.getX(), this.getY() + 1.3, this.getZ(), 1, 0, 0, 0, 0);
                this.startHover();
            }
            return;
        }

        if (target == null) {
            // Nobody to chase: drift back over the altar.
            Vec3 towards = this.home().add(0, HOVER_HEIGHT + 6, 0).subtract(this.position());
            this.setDeltaMovement(towards.scale(0.03));
            return;
        }

        Vec3 targetCentre = target.getBoundingBox().getCenter();
        switch (this.stage) {
            case HOVER -> {
                this.circle += enraged ? 0.05 : 0.03;
                Vec3 wanted = targetCentre.add(Math.cos(this.circle) * HOVER_RADIUS, HOVER_HEIGHT, Math.sin(this.circle) * HOVER_RADIUS);
                Vec3 steer = wanted.subtract(this.position()).scale(0.08);
                double max = enraged ? 0.8 : 0.6;
                if (steer.length() > max) {
                    steer = steer.normalize().scale(max);
                }
                this.setDeltaMovement(this.getDeltaMovement().scale(0.7).add(steer.scale(0.3)));
                this.faceTowards(targetCentre.subtract(this.position()));
                if (this.stageTicks > (enraged ? 40 : 70)) {
                    this.stage = Stage.WIND_UP;
                    this.stageTicks = 0;
                    this.chargesLeft = enraged ? 4 : 3;
                }
            }
            case WIND_UP -> {
                this.setDeltaMovement(this.getDeltaMovement().scale(0.75));
                this.faceTowards(targetCentre.subtract(this.position()));
                if (this.stageTicks > (enraged ? 6 : 12)) {
                    Vec3 direction = targetCentre.subtract(this.position().add(0, 1.3, 0)).normalize();
                    this.setDeltaMovement(direction.scale(enraged ? 1.5 : 1.1));
                    this.faceTowards(direction);
                    this.playSound(SoundEvents.RAVAGER_ROAR, 2.0F, enraged ? 1.6F : 1.3F);
                    this.stage = Stage.CHARGE;
                    this.stageTicks = 0;
                }
            }
            case CHARGE -> {
                if (this.stageTicks > 16) {
                    this.setDeltaMovement(this.getDeltaMovement().scale(0.85));
                }
                if (this.stageTicks > 24) {
                    if (--this.chargesLeft > 0) {
                        this.stage = Stage.WIND_UP;
                        this.stageTicks = 0;
                    } else {
                        this.startHover();
                    }
                }
            }
            default -> this.startHover();
        }
    }

    private void startHover() {
        this.stage = Stage.HOVER;
        this.stageTicks = 0;
    }

    @Override
    protected boolean canFight(Player player) {
        // Anywhere near its altar; it gives up on anyone who runs a long way off.
        return this.door == null || player.position().distanceToSqr(this.home()) < 80.0 * 80.0;
    }

    @Override
    protected void dropLoot(ServerLevel level, boolean firstKill) {
        this.spawnAtLocation(level, new ItemStack(Items.DIAMOND, 2 + this.random.nextInt(3)));
        this.spawnAtLocation(level, new ItemStack(Items.GOLD_INGOT, 6 + this.random.nextInt(7)));
        this.spawnAtLocation(level, new ItemStack(Items.ENDER_EYE, 1 + this.random.nextInt(3)));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.PHANTOM_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SLIME_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENDER_DRAGON_DEATH;
    }
}
