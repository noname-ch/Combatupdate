package ch.bbcag.gipfeliarmy.terraria;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

// The Wall of Flesh. It fills its tunnel from side to side and floor to roof, and crawls along it
// from the far end towards the hellevator, faster the more it is hurt. There is no getting past it:
// whoever is in the tunnel has to kill it before it reaches the end, or climb out and give up.
// Its two eyes fire lasers the whole way, each one flaring up just before it fires, and twice as
// often once it is under half health, when The Hungry start tearing loose from it as well. Under a
// quarter both eyes fire together, three lasers each. Whoever runs too far ahead of it is caught
// by its tongue and dragged back, as in Terraria.
//
// It always faces east, the way the tunnel runs, and never turns: in Terraria it only ever
// moves one way, and a wall that turned round would be a very strange wall.
public final class WallOfFlesh extends TerrariaBoss {
    private static final float CONTACT = 10.0F;
    private static final float LASER_DAMAGE = 5.0F;
    // How long before a laser its eye flares.
    private static final int LASER_WARNING = 8;
    // How spread out a desperate volley is, in radians either side.
    private static final float VOLLEY_SPREAD = 0.12F;

    // The Hungry: how many at once, how often, and what they are.
    private static final int MAX_HUNGRY = 3;
    private static final int HUNGRY_EVERY = 100;
    private static final double HUNGRY_HEALTH = 14.0;
    private static final double HUNGRY_DAMAGE = 5.0;
    private static final int HUNGRY_LIFE = 30 * 20;

    // How far ahead of its face a player may get before its tongue drags them back.
    private static final double TONGUE_REACH = 30.0;

    // Blocks a tick: slow at full health, a jog near death.
    private static final double MIN_SPEED = 0.035;
    private static final double MAX_SPEED = 0.10;

    // Half its width, which is also half the tunnel's (see ArenaBuilder).
    private static final double HALF_WIDTH = 2.5;

    // Where on its face the eyes are, up from its feet.
    private static final double[] EYE_HEIGHTS = {7.6, 2.6};

    private int laserCooldown = 60;
    private int eye;

    public WallOfFlesh(EntityType<? extends WallOfFlesh> type, Level level) {
        super(type, level);
        this.xpReward = 300;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 750.0)
                .add(Attributes.ARMOR, 6.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 96.0);
    }

    // Where its centre starts: at the tunnel's west end, filling it.
    static double startX(int doorX) {
        return doorX - ArenaBuilder.TUNNEL_LENGTH + 1 + HALF_WIDTH;
    }

    // And where it stops: at the east end, under the shaft.
    private double endX() {
        return this.door == null ? Double.MAX_VALUE : this.door.getX() + ArenaBuilder.TUNNEL_HALF_WIDTH + 1 - HALF_WIDTH;
    }

    @Override
    public BossKind kind() {
        return BossKind.WALL_OF_FLESH;
    }

    @Override
    protected float contactDamage() {
        return CONTACT;
    }

    @Override
    protected void onEnrage(ServerLevel level) {
        this.playSound(SoundEvents.RAVAGER_ROAR, 4.0F, 0.5F);
    }

    @Override
    protected void bossTick(ServerLevel level, @Nullable Player target) {
        // East, and only east.
        this.setYRot(-90.0F);
        this.yBodyRot = -90.0F;
        this.yHeadRot = -90.0F;
        this.setXRot(0.0F);

        float hurt = 1.0F - this.getHealth() / this.getMaxHealth();
        double speed = MIN_SPEED + (MAX_SPEED - MIN_SPEED) * hurt;
        if (this.getX() + speed > this.endX()) {
            speed = Math.max(0.0, this.endX() - this.getX());
        }
        this.setDeltaMovement(speed, 0.0, 0.0);
        if (this.door != null) {
            // Held to the tunnel's floor and middle, whatever the physics thinks.
            this.setPos(this.getX(), this.inner + 1, this.door.getZ() + 0.5);
        }

        if (this.tickCount % 40 == 0) {
            this.playSound(SoundEvents.WARDEN_HEARTBEAT, 3.0F, 0.6F);
        }

        if (target == null) {
            return;
        }

        boolean desperate = this.isDesperate();
        int next = (this.eye + 1) % EYE_HEIGHTS.length;
        if (--this.laserCooldown <= LASER_WARNING) {
            if (desperate) {
                for (int i = 0; i < EYE_HEIGHTS.length; i++) {
                    this.telegraph(level, this.eyeAt(i), LASER_WARNING - this.laserCooldown, 0.5F);
                }
            } else {
                this.telegraph(level, this.eyeAt(next), LASER_WARNING - this.laserCooldown, 0.5F);
            }
        }
        if (this.laserCooldown <= 0) {
            this.eye = next;
            float damage = this.scaled(LASER_DAMAGE);
            if (desperate) {
                for (int i = 0; i < EYE_HEIGHTS.length; i++) {
                    Vec3 from = this.eyeAt(i);
                    Vec3 aim = target.getBoundingBox().getCenter().subtract(from);
                    for (int shot = -1; shot <= 1; shot++) {
                        level.addFreshEntity(new BossBolt(level, this, from, aim.yRot(shot * VOLLEY_SPREAD), 1.2, damage, BossBolt.Kind.LASER));
                    }
                }
            } else {
                Vec3 from = this.eyeAt(this.eye);
                Vec3 direction = target.getBoundingBox().getCenter().subtract(from);
                level.addFreshEntity(new BossBolt(level, this, from, direction, 1.1, damage, BossBolt.Kind.LASER));
            }
            this.playSound(SoundEvents.BLAZE_SHOOT, 2.0F, desperate ? 1.2F : 1.6F);
            this.laserCooldown = desperate ? 30 : this.isEnraged() ? 16 : 28;
        }

        if (this.isEnraged() && this.tickCount % HUNGRY_EVERY == 0 && this.minions(level).size() < MAX_HUNGRY) {
            Vec3 mouth = new Vec3(this.getX() + HALF_WIDTH + 0.8, this.getY() + 5.0, this.getZ());
            this.summonMinion(level, mouth, "the_hungry", HUNGRY_HEALTH, HUNGRY_DAMAGE, HUNGRY_LIFE);
            this.playSound(SoundEvents.RAVAGER_ATTACK, 3.0F, 0.6F);
        }

        this.tongue(level, target);
    }

    private Vec3 eyeAt(int eye) {
        return new Vec3(this.getX() + HALF_WIDTH + 0.3, this.getY() + EYE_HEIGHTS[eye], this.getZ());
    }

    // Anyone who has run far ahead of it in the tunnel is hauled back towards it and slowed, so
    // the fight cannot be dodged by running to the far end and waiting.
    private void tongue(ServerLevel level, Player target) {
        if (this.door == null || this.tickCount % 10 != 0) {
            return;
        }
        double ahead = target.getX() - (this.getX() + HALF_WIDTH);
        if (ahead <= TONGUE_REACH) {
            return;
        }

        Vec3 face = new Vec3(this.getX() + HALF_WIDTH, target.getY(), target.getZ());
        Vec3 pull = face.subtract(target.position()).normalize().scale(1.6).add(0, 0.3, 0);
        target.setDeltaMovement(pull);
        target.needsSync = true;
        target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1), this);
        target.sendOverlayMessage(Component.translatable("gipfeliarmy.terraria.tongued").withStyle(ChatFormatting.RED));
        this.playSound(SoundEvents.FROG_TONGUE, 4.0F, 0.4F);
        Vec3 step = target.position().subtract(face).scale(1.0 / 12);
        for (int i = 0; i <= 12; i++) {
            Vec3 at = face.add(step.scale(i));
            level.sendParticles(new DustParticleOptions(0xC0306A, 1.5F), at.x, at.y + 1.0, at.z, 1, 0.05, 0.05, 0.05, 0.0);
        }
    }

    @Override
    protected boolean canFight(Player player) {
        if (this.door == null) {
            return true;
        }
        BossArenas.Arena arena = null;
        if (this.level() instanceof ServerLevel level) {
            arena = BossArenas.get(level.getServer()).arena(BossKind.WALL_OF_FLESH);
        }
        return arena == null || ArenaBuilder.inside(arena, player.position());
    }

    @Override
    protected void dropLoot(ServerLevel level, boolean firstKill) {
        this.spawnAtLocation(level, new ItemStack(Items.NETHERITE_SCRAP, 2 + this.random.nextInt(3)));
        this.spawnAtLocation(level, new ItemStack(Items.BLAZE_ROD, 4 + this.random.nextInt(5)));
        this.spawnAtLocation(level, new ItemStack(Items.EXPERIENCE_BOTTLE, 8 + this.random.nextInt(9)));
        this.spawnAtLocation(level, this.relic(level, Items.DIAMOND_SWORD, "breaker_blade",
                Enchantments.SHARPNESS, 5, Enchantments.FIRE_ASPECT, 2, Enchantments.KNOCKBACK, 2, Enchantments.UNBREAKING, 3));
        // In Terraria it hands over a Demon Heart the first time; a second life is the nearest thing.
        if (firstKill) {
            this.spawnAtLocation(level, new ItemStack(Items.TOTEM_OF_UNDYING));
        }
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.RAVAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.RAVAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.RAVAGER_DEATH;
    }
}
