package ch.bbcag.gipfeliarmy.terraria;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

// Plantera. She bursts out of her bulb when someone comes into her cave, and is held to it by
// the vines she hangs from: she chases whoever is there, but never further from the bulb than her
// vines reach. Seeds come at a steady rate, every third volley a spread of poisoned ones.
//
// At half health the bud opens into a mouth. She moves faster, bites harder, and throws rings of
// thorn balls out in every direction instead of aiming seeds.
//
// Beating her for the first time always drops the Last Prism; after that it is one in three.
public final class Plantera extends TerrariaBoss {
    private static final float CONTACT = 8.0F;
    private static final float CONTACT_ENRAGED = 12.0F;
    private static final float SEED_DAMAGE = 4.0F;
    private static final float THORN_DAMAGE = 6.0F;

    // How far from the bulb her vines let her go.
    private static final double TETHER = 16.0;

    private int shotCooldown = 40;
    private int volley;

    public Plantera(EntityType<? extends Plantera> type, Level level) {
        super(type, level);
        this.xpReward = 500;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 800.0)
                .add(Attributes.ARMOR, 8.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    public BossKind kind() {
        return BossKind.PLANTERA;
    }

    @Override
    protected float contactDamage() {
        return this.isEnraged() ? CONTACT_ENRAGED : CONTACT;
    }

    @Override
    protected void onEnrage(ServerLevel level) {
        this.playSound(SoundEvents.RAVAGER_ROAR, 3.0F, 1.2F);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.CONCRETE.pick(DyeColor.PINK).defaultBlockState()),
                this.getX(), this.getY() + 1.2, this.getZ(), 60, 1.0, 1.0, 1.0, 0.2);
    }

    @Override
    protected void bossTick(ServerLevel level, @Nullable Player target) {
        boolean enraged = this.isEnraged();
        Vec3 home = this.home();
        Vec3 centre = this.position().add(0, 1.2, 0);

        Vec3 wanted = target == null ? home : target.getBoundingBox().getCenter();
        // Kept within reach of the bulb.
        Vec3 fromHome = wanted.subtract(home);
        if (this.door != null && fromHome.length() > TETHER) {
            wanted = home.add(fromHome.normalize().scale(TETHER));
        }

        Vec3 steer = wanted.subtract(centre);
        double speed = enraged ? 0.3 : 0.18;
        if (steer.length() > speed) {
            steer = steer.normalize().scale(speed);
        }
        this.setDeltaMovement(this.getDeltaMovement().scale(0.75).add(steer.scale(0.25)));

        if (target == null) {
            return;
        }

        Vec3 aim = target.getBoundingBox().getCenter().subtract(centre);
        this.faceTowards(aim);

        if (--this.shotCooldown > 0) {
            return;
        }

        this.volley++;
        if (enraged) {
            // A ring of thorn balls, tipped a little towards the target.
            int count = 8;
            double offset = this.random.nextDouble() * Math.PI;
            for (int i = 0; i < count; i++) {
                double angle = offset + i * Math.PI * 2.0 / count;
                Vec3 direction = new Vec3(Math.cos(angle), aim.normalize().y * 0.5, Math.sin(angle));
                level.addFreshEntity(new BossBolt(level, this, centre, direction, 0.6, THORN_DAMAGE, BossBolt.Kind.THORN_BALL));
            }
            this.playSound(SoundEvents.PUFFER_FISH_BLOW_OUT, 3.0F, 0.6F);
            this.shotCooldown = 45;
        } else if (this.volley % 3 == 0) {
            // A fan of poisoned seeds.
            for (int i = -2; i <= 2; i++) {
                Vec3 direction = aim.normalize().yRot(i * 0.18F);
                level.addFreshEntity(new BossBolt(level, this, centre, direction, 0.8, SEED_DAMAGE, BossBolt.Kind.POISON_SEED));
            }
            this.playSound(SoundEvents.LLAMA_SPIT, 3.0F, 0.7F);
            this.shotCooldown = 30;
        } else {
            level.addFreshEntity(new BossBolt(level, this, centre, aim, 0.9, SEED_DAMAGE, BossBolt.Kind.SEED));
            this.playSound(SoundEvents.LLAMA_SPIT, 3.0F, 1.0F);
            this.shotCooldown = 18;
        }
    }

    @Override
    protected boolean canFight(Player player) {
        return this.door == null || player.position().distanceToSqr(this.home()) < 40.0 * 40.0;
    }

    @Override
    protected void dropLoot(ServerLevel level, boolean firstKill) {
        if (firstKill || this.random.nextInt(3) == 0) {
            this.spawnAtLocation(level, new ItemStack(TerrariaContent.LAST_PRISM.get()));
        }
        this.spawnAtLocation(level, new ItemStack(Items.EMERALD, 8 + this.random.nextInt(9)));
        this.spawnAtLocation(level, new ItemStack(Items.GOLDEN_APPLE, 2 + this.random.nextInt(2)));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.BIG_DRIPLEAF_TILT_DOWN;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.AZALEA_LEAVES_BREAK;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.RAVAGER_DEATH;
    }
}
