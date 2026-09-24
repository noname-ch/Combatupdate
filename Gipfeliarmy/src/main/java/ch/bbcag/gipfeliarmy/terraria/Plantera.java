package ch.bbcag.gipfeliarmy.terraria;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

// Plantera. She bursts out of her bulb when someone comes into her cave, and is held to it by
// the vines she hangs from: she chases whoever is there, but never further from the bulb than her
// vines reach. Seeds come at a steady rate, every third volley a spread of poisoned ones, which
// she swells up for first.
//
// At half health the bud opens into a mouth. She moves faster, bites harder, and throws rings of
// thorn balls out in every direction instead of aiming seeds. She lets loose spores that drift
// after whoever she is fighting, and every few seconds she draws back, glowing, and lunges as far
// as her vines let her. Under a quarter the rings get thicker and the lunges come sooner.
//
// Beating her for the first time always drops the Last Prism; after that it is one in three.
public final class Plantera extends TerrariaBoss {
    private static final float CONTACT = 8.0F;
    private static final float CONTACT_ENRAGED = 12.0F;
    private static final float SEED_DAMAGE = 4.0F;
    private static final float THORN_DAMAGE = 6.0F;

    // How far from the bulb her vines let her go.
    private static final double TETHER = 16.0;

    // How long a warning comes before a fan of poisoned seeds or a ring of thorns.
    private static final int SHOT_WARNING = 8;
    // Lunges: how long she draws back first, how long one lasts, and how fast it is.
    private static final int LUNGE_WIND_UP = 14;
    private static final int LUNGE_TICKS = 14;
    private static final double LUNGE_SPEED = 1.3;

    private int shotCooldown = 40;
    private int volley;
    private int sporeCooldown = 60;
    private int lungeCooldown = 100;
    // Ticks into drawing back for a lunge, or -1 when she is not; and ticks left of one under way.
    private int lungeWindUp = -1;
    private int lunging;

    public Plantera(EntityType<? extends Plantera> type, Level level) {
        super(type, level);
        this.xpReward = 500;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 1000.0)
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
        boolean desperate = this.isDesperate();
        Vec3 home = this.home();
        Vec3 centre = this.position().add(0, 1.2, 0);

        if (this.lunging > 0) {
            // Flying where the lunge threw her, until it runs out or her vines pull taut.
            this.lunging--;
            this.faceTowards(this.getDeltaMovement());
            level.sendParticles(new DustParticleOptions(0xE87BD0, 1.5F), centre.x, centre.y, centre.z, 3, 0.8, 0.8, 0.8, 0.0);
            if (this.door != null && centre.distanceTo(home) > TETHER + 4.0) {
                this.lunging = 0;
            }
            return;
        }

        Vec3 wanted = target == null ? home : target.getBoundingBox().getCenter();
        // Kept within reach of the bulb.
        Vec3 fromHome = wanted.subtract(home);
        if (this.door != null && fromHome.length() > TETHER) {
            wanted = home.add(fromHome.normalize().scale(TETHER));
        }

        Vec3 steer = wanted.subtract(centre);
        double speed = desperate ? 0.36 : enraged ? 0.3 : 0.18;
        if (steer.length() > speed) {
            steer = steer.normalize().scale(speed);
        }
        this.setDeltaMovement(this.getDeltaMovement().scale(0.75).add(steer.scale(0.25)));

        if (target == null) {
            this.lungeWindUp = -1;
            return;
        }

        Vec3 aim = target.getBoundingBox().getCenter().subtract(centre);
        this.faceTowards(aim);

        if (enraged && this.lunge(level, centre, aim, desperate)) {
            return;
        }

        if (enraged && --this.sporeCooldown <= 0) {
            for (int i = -1; i <= 1; i++) {
                Vec3 direction = aim.normalize().yRot(i * 0.6F).add(0, 0.3, 0);
                level.addFreshEntity(new BossBolt(level, this, centre, direction, 0.35, this.scaled(SEED_DAMAGE), BossBolt.Kind.SPORE));
            }
            this.playSound(SoundEvents.PUFFER_FISH_BLOW_UP, 3.0F, 0.5F);
            this.sporeCooldown = desperate ? 40 : 70;
        }

        if (--this.shotCooldown > 0) {
            // A ring of thorns, or a fan of poisoned seeds, is signalled before it comes.
            boolean bigVolley = enraged || (this.volley + 1) % 3 == 0;
            if (bigVolley && this.shotCooldown <= SHOT_WARNING) {
                this.telegraph(level, centre, SHOT_WARNING - this.shotCooldown, 1.0F);
            }
            return;
        }

        this.volley++;
        if (enraged) {
            // A ring of thorn balls, tipped a little towards the target.
            int count = desperate ? 12 : 8;
            double offset = this.random.nextDouble() * Math.PI;
            for (int i = 0; i < count; i++) {
                double angle = offset + i * Math.PI * 2.0 / count;
                Vec3 direction = new Vec3(Math.cos(angle), aim.normalize().y * 0.5, Math.sin(angle));
                level.addFreshEntity(new BossBolt(level, this, centre, direction, 0.6, this.scaled(THORN_DAMAGE), BossBolt.Kind.THORN_BALL));
            }
            this.playSound(SoundEvents.PUFFER_FISH_BLOW_OUT, 3.0F, 0.6F);
            this.shotCooldown = desperate ? 35 : 45;
        } else if (this.volley % 3 == 0) {
            // A fan of poisoned seeds.
            for (int i = -2; i <= 2; i++) {
                Vec3 direction = aim.normalize().yRot(i * 0.18F);
                level.addFreshEntity(new BossBolt(level, this, centre, direction, 0.8, this.scaled(SEED_DAMAGE), BossBolt.Kind.POISON_SEED));
            }
            this.playSound(SoundEvents.LLAMA_SPIT, 3.0F, 0.7F);
            this.shotCooldown = 30;
        } else {
            level.addFreshEntity(new BossBolt(level, this, centre, aim, 0.9, this.scaled(SEED_DAMAGE), BossBolt.Kind.SEED));
            this.playSound(SoundEvents.LLAMA_SPIT, 3.0F, 1.0F);
            this.shotCooldown = 18;
        }
    }

    // Drawing back and lunging. True while she is drawing back, when nothing else happens.
    private boolean lunge(ServerLevel level, Vec3 centre, Vec3 aim, boolean desperate) {
        if (this.lungeWindUp < 0) {
            if (--this.lungeCooldown > 0) {
                return false;
            }
            this.lungeWindUp = 0;
        }

        this.setDeltaMovement(this.getDeltaMovement().scale(0.5).add(aim.normalize().scale(-0.03)));
        this.telegraph(level, centre, this.lungeWindUp, 1.2F);
        if (++this.lungeWindUp > LUNGE_WIND_UP) {
            this.setDeltaMovement(aim.normalize().scale(desperate ? LUNGE_SPEED * 1.25 : LUNGE_SPEED));
            this.playSound(SoundEvents.RAVAGER_ROAR, 3.0F, 1.5F);
            this.lunging = LUNGE_TICKS;
            this.lungeWindUp = -1;
            this.lungeCooldown = desperate ? 60 : 100;
        }
        return true;
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
        this.spawnAtLocation(level, this.relic(level, Items.NETHERITE_SWORD, "seedler",
                Enchantments.SHARPNESS, 5, Enchantments.SWEEPING_EDGE, 3, Enchantments.LOOTING, 3, Enchantments.UNBREAKING, 3));
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
