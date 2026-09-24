package ch.bbcag.gipfeliarmy.terraria;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// What the three Terraria bosses have in common: a boss bar, a second phase at half health, a
// lair they belong to, damage to whatever they touch, and flight straight through the terrain the
// way Terraria's bosses fly through it.
//
// None of them is saved with the world. A boss whose players have all left gives up and vanishes
// (see LONELY_TICKS), and a boss in a chunk that unloads goes with it; either way it is woken again,
// at full health, the next time someone walks into its lair (see BossArenas). What dying does is
// the only thing that sticks.
public abstract class TerrariaBoss extends Monster {
    private static final EntityDataAccessor<Boolean> DATA_ENRAGED =
            SynchedEntityData.defineId(TerrariaBoss.class, EntityDataSerializers.BOOLEAN);

    // How long a boss waits with nobody near before it goes back to sleep.
    private static final int LONELY_TICKS = 30 * 20;
    private static final double LONELY_RANGE = 96.0;

    private final ServerBossEvent bossEvent = new ServerBossEvent(UUID.randomUUID(), this.getDisplayName(),
            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);

    protected @Nullable BlockPos door;
    protected int inner;
    private int lonelyTicks;

    protected TerrariaBoss(EntityType<? extends TerrariaBoss> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
    }

    public abstract BossKind kind();

    // How hard touching it hurts, in each phase.
    protected abstract float contactDamage();

    // Called once, on the server, the tick the boss drops under half health.
    protected void onEnrage(ServerLevel level) {
    }

    // The boss's own AI, run once a server tick after the common part.
    protected abstract void bossTick(ServerLevel level, @Nullable Player target);

    // What it leaves behind. First kill is whether this lair's boss has never been beaten before.
    protected abstract void dropLoot(ServerLevel level, boolean firstKill);

    public void setArena(BossArenas.Arena arena) {
        this.door = arena.door();
        this.inner = arena.inner();
    }

    // The middle of its lair, or where it is if it has none (a boss summoned by command).
    protected Vec3 home() {
        return this.door == null ? this.position() : ArenaBuilder.home(this.door, this.inner, this.kind());
    }

    public boolean isEnraged() {
        return this.entityData.get(DATA_ENRAGED);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        super.defineSynchedData(entityData);
        entityData.define(DATA_ENRAGED, false);
    }

    @Override
    public void tick() {
        // Through walls, like the Vex; see the class comment.
        this.noPhysics = true;
        super.tick();
        this.noPhysics = false;
        this.setNoGravity(true);
    }

    @Override
    public void travel(Vec3 input) {
        // Every boss sets its own velocity each tick; nothing here adds drag or gravity to it.
        this.move(MoverType.SELF, this.getDeltaMovement());
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        this.bossEvent.setProgress(this.getHealth() / this.getMaxHealth());

        if (!this.isEnraged() && this.getHealth() < this.getMaxHealth() * 0.5F) {
            this.entityData.set(DATA_ENRAGED, true);
            this.onEnrage(level);
        }

        Player target = this.pickTarget(level);
        this.setTarget(target);

        if (target == null) {
            if (++this.lonelyTicks > LONELY_TICKS) {
                this.discard();
                return;
            }
        } else {
            this.lonelyTicks = 0;
        }

        this.bossTick(level, target);
        this.touch(level);
    }

    // The nearest player it can fight, alive and in survival or adventure.
    private @Nullable Player pickTarget(ServerLevel level) {
        Player best = null;
        double bestDistance = LONELY_RANGE * LONELY_RANGE;
        for (ServerPlayer player : level.players()) {
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
                continue;
            }
            double distance = player.distanceToSqr(this);
            if (distance < bestDistance && this.canFight(player)) {
                bestDistance = distance;
                best = player;
            }
        }

        return best;
    }

    // Whether this player is somewhere the boss will fight them. Each boss narrows it to its lair.
    protected boolean canFight(Player player) {
        return true;
    }

    // Anything alive that it is touching takes its contact damage and is thrown clear.
    private void touch(ServerLevel level) {
        List<LivingEntity> touching = level.getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(0.1),
                entity -> entity != this && entity.isAlive() && !(entity instanceof TerrariaBoss)
                        && !(entity instanceof Player player && (player.isCreative() || player.isSpectator())));
        DamageSource source = this.damageSources().mobAttack(this);
        for (LivingEntity victim : touching) {
            if (victim.hurtServer(level, source, this.contactDamage())) {
                Vec3 away = victim.position().subtract(this.position());
                victim.knockback(0.8, -away.x, -away.z, source, this.contactDamage());
            }
        }
    }

    protected void faceTowards(Vec3 direction) {
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        float yaw = (float) (Mth.atan2(direction.z, direction.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) (-(Mth.atan2(direction.y, horizontal) * Mth.RAD_TO_DEG));
        this.setYRot(yaw);
        this.setXRot(pitch);
        this.yBodyRot = yaw;
        this.yHeadRot = yaw;
    }

    // --- Being a boss --------------------------------------------------------------------------

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        this.bossEvent.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossEvent.removePlayer(player);
    }

    @Override
    public void setCustomName(@Nullable Component name) {
        super.setCustomName(name);
        this.bossEvent.setName(this.getDisplayName());
    }

    @Override
    public void die(DamageSource source) {
        boolean wasAlive = !this.isRemoved() && !this.dead;
        super.die(source);
        if (!wasAlive || !(this.level() instanceof ServerLevel level)) {
            return;
        }

        this.bossEvent.setProgress(0.0F);
        if (this.door != null) {
            BossArenas.get(level.getServer()).defeated(level.getServer(), this.kind());
        }

        Component message = Component.translatable("gipfeliarmy.terraria.defeated", this.kind().displayName())
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(this) < 200.0 * 200.0) {
                player.sendSystemMessage(message);
            }
        }
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
        super.dropCustomDeathLoot(level, source, killedByPlayer);
        boolean firstKill = true;
        if (this.door != null) {
            BossArenas.Arena arena = BossArenas.get(level.getServer()).arena(this.kind());
            firstKill = arena == null || arena.defeats() == 0;
        }
        this.dropLoot(level, firstKill);
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        // Flying through the ground is how they move, not something to choke on.
        if (source.is(DamageTypes.IN_WALL) || source.is(DamageTypes.DROWN) || source.is(DamageTypes.FALL)
                || source.is(DamageTypes.CRAMMING)) {
            return true;
        }
        return super.isInvulnerableTo(level, source);
    }

    @Override
    protected void checkFallDamage(double ya, boolean onGround, BlockState onState, BlockPos pos) {
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void pushEntities() {
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    public boolean canUsePortal(boolean ignorePassenger) {
        return false;
    }

    @Override
    public SoundSource getSoundSource() {
        return SoundSource.HOSTILE;
    }

    @Override
    protected float getSoundVolume() {
        return 4.0F;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        // A boss's own shots do not hurt it, nor one another.
        Entity attacker = source.getEntity();
        if (attacker instanceof TerrariaBoss) {
            return false;
        }
        return super.hurtServer(level, source, damage);
    }
}
