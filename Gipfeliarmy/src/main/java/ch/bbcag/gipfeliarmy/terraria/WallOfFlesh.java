package ch.bbcag.gipfeliarmy.terraria;

import org.jspecify.annotations.Nullable;

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

// The Wall of Flesh. It fills its tunnel from side to side and floor to roof, and crawls along it
// from the far end towards the hellevator, faster the more it is hurt. There is no getting past it:
// whoever is in the tunnel has to kill it before it reaches the end, or climb out and give up.
// Its two eyes fire lasers the whole way, twice as often once it is under half health.
//
// It always faces east, the way the tunnel runs, and never turns: in Terraria it only ever
// moves one way, and a wall that turned round would be a very strange wall.
public final class WallOfFlesh extends TerrariaBoss {
    private static final float CONTACT = 10.0F;
    private static final float LASER_DAMAGE = 5.0F;

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
                .add(Attributes.MAX_HEALTH, 600.0)
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

        if (target != null && --this.laserCooldown <= 0) {
            this.eye = (this.eye + 1) % EYE_HEIGHTS.length;
            Vec3 from = new Vec3(this.getX() + HALF_WIDTH + 0.3, this.getY() + EYE_HEIGHTS[this.eye], this.getZ());
            Vec3 direction = target.getBoundingBox().getCenter().subtract(from);
            level.addFreshEntity(new BossBolt(level, this, from, direction, 1.1, LASER_DAMAGE, BossBolt.Kind.LASER));
            this.playSound(SoundEvents.BLAZE_SHOOT, 2.0F, 1.6F);
            this.laserCooldown = this.isEnraged() ? 12 : 25;
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
