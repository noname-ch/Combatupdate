package ch.bbcag.gipfeliarmy.terraria;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.gipfeliarmy.Config;
import ch.bbcag.gipfeliarmy.GipfeliArmyMod;

// What the three Terraria bosses have in common: a boss bar, a second phase at half health, a
// lair they belong to, damage to whatever they touch, and flight straight through the terrain the
// way Terraria's bosses fly through it.
//
// On top of that, what makes each fight a fight: they grow tougher for every player who joins in
// (see scaleForFighters), warn before their big attacks (see telegraph), may call in minions of
// their own (see summonMinion), go into a last frenzy under a quarter of their health (see
// isDesperate), and each drops something of its own besides the raw materials (see relic).
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

    // What each player past the first adds: to health, as a share of the base, and to damage.
    private static final double HEALTH_PER_FIGHTER = 0.6;
    private static final float DAMAGE_PER_FIGHTER = 0.15F;
    private static final int MAX_FIGHTERS = 8;
    private static final Identifier SCALING_ID = Identifier.fromNamespaceAndPath(GipfeliArmyMod.MODID, "boss_scaling");

    // Carried by everything a boss summons, so its own contact damage and shots leave them alone
    // and they go when it does.
    static final String MINION_TAG = GipfeliArmyMod.MODID + ".boss_minion";

    private final ServerBossEvent bossEvent = new ServerBossEvent(UUID.randomUUID(), this.getDisplayName(),
            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);

    protected @Nullable BlockPos door;
    protected int inner;
    private int lonelyTicks;
    // How many players its health has been raised for; 0 until the first time it looks.
    private int fighters;

    protected TerrariaBoss(EntityType<? extends TerrariaBoss> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.bossEvent.setColor(this.kind().barColour());
        this.bossEvent.setDarkenScreen(true);
        this.bossEvent.setPlayBossMusic(true);
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
        if (this.fighters == 0 || this.tickCount % 20 == 0) {
            this.scaleForFighters(level);
        }
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

    // Raises its health for everyone who has come to fight it, the way Terraria's bosses grow in
    // multiplayer. Only ever up: a player who dies or runs off does not heal it or make it weaker,
    // and the share of health it has already lost stays lost.
    private void scaleForFighters(ServerLevel level) {
        int count = 0;
        for (ServerPlayer player : level.players()) {
            if (player.isAlive() && !player.isSpectator() && !player.isCreative()
                    && player.distanceToSqr(this) < LONELY_RANGE * LONELY_RANGE && this.canFight(player)) {
                count++;
            }
        }
        int wanted = Math.clamp(count, 1, MAX_FIGHTERS);
        if (wanted <= this.fighters) {
            return;
        }

        AttributeInstance maxHealth = this.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        float share = this.fighters == 0 ? 1.0F : this.getHealth() / this.getMaxHealth();
        double bonus = Config.TERRARIA_BOSS_HEALTH.get() - 1.0 + HEALTH_PER_FIGHTER * (wanted - 1);
        maxHealth.addOrReplacePermanentModifier(new AttributeModifier(SCALING_ID, bonus, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        this.setHealth(this.getMaxHealth() * share);
        this.fighters = wanted;
    }

    // A hit's damage as the configured difficulty and the number of players make it.
    protected float scaled(float damage) {
        float perFighter = 1.0F + DAMAGE_PER_FIGHTER * (Math.max(1, this.fighters) - 1);
        return damage * (float) Config.TERRARIA_BOSS_DAMAGE.getAsDouble() * perFighter;
    }

    // Under a quarter of its health: the last stretch, where each boss throws everything it has.
    protected boolean isDesperate() {
        return this.getHealth() < this.getMaxHealth() * 0.25F;
    }

    // A warning that something big is coming from here: a puff of the boss's colour and, on the
    // first tick of it, a sound. Called every tick of a wind-up.
    protected void telegraph(ServerLevel level, Vec3 at, int ticksIn, float spread) {
        level.sendParticles(new DustParticleOptions(this.kind().colour() & 0xFFFFFF, 2.0F),
                at.x, at.y, at.z, 6, spread, spread, spread, 0.0);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 2, spread, spread, spread, 0.05);
        if (ticksIn == 0) {
            this.playSound(SoundEvents.WARDEN_SONIC_CHARGE, 2.0F, 1.4F);
        }
    }

    // --- Minions -------------------------------------------------------------------------------

    // Calls in a minion: a Vex, which already flies through walls and goes for its owner's target,
    // renamed and cut down to size. It lives for the given time at most.
    protected void summonMinion(ServerLevel level, Vec3 at, String name, double health, double damage, int lifeTicks) {
        Vex minion = EntityTypes.VEX.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (minion == null) {
            return;
        }

        minion.setPos(at);
        minion.setOwner(this);
        minion.setLimitedLife(lifeTicks);
        minion.setCustomName(Component.translatable("gipfeliarmy.terraria." + name).withStyle(ChatFormatting.RED));
        minion.addTag(MINION_TAG);
        setBase(minion, Attributes.MAX_HEALTH, health);
        setBase(minion, Attributes.ATTACK_DAMAGE, this.scaled((float) damage));
        minion.setHealth(minion.getMaxHealth());
        minion.setTarget(this.getTarget());
        level.addFreshEntity(minion);
        level.sendParticles(new DustParticleOptions(0x8A0303, 1.5F), at.x, at.y, at.z, 12, 0.3, 0.3, 0.3, 0.0);
    }

    private static void setBase(LivingEntity entity, Holder<Attribute> attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    protected List<Vex> minions(ServerLevel level) {
        return level.getEntitiesOfClass(Vex.class, this.getBoundingBox().inflate(LONELY_RANGE),
                vex -> vex.isAlive() && vex.entityTags().contains(MINION_TAG) && vex.getOwner() == this);
    }

    public static boolean isMinion(Entity entity) {
        return entity.entityTags().contains(MINION_TAG);
    }

    @Override
    public void remove(RemovalReason reason) {
        // Its minions go with it, whether it died, gave up or was unloaded.
        if (this.level() instanceof ServerLevel level) {
            for (Vex minion : this.minions(level)) {
                level.sendParticles(ParticleTypes.POOF, minion.getX(), minion.getY() + 0.4, minion.getZ(), 6, 0.2, 0.2, 0.2, 0.02);
                minion.discard();
            }
        }
        super.remove(reason);
    }

    // --- Loot ----------------------------------------------------------------------------------

    // Something only this boss drops: a vanilla item under its own name, enchanted, marked as
    // coming from the boss. Enchantments go as key, level, key, level.
    protected ItemStack relic(ServerLevel level, Item item, String name, Object... enchantments) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("gipfeliarmy.terraria.loot." + name)
                .withStyle(style -> style.withItalic(false).withColor(this.kind().colour() & 0xFFFFFF)));
        stack.set(DataComponents.RARITY, Rarity.EPIC);
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.translatable("gipfeliarmy.terraria.loot.from", this.kind().displayName())
                .withStyle(style -> style.withItalic(true).withColor(ChatFormatting.DARK_PURPLE)))));
        var registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        for (int i = 0; i + 1 < enchantments.length; i += 2) {
            @SuppressWarnings("unchecked")
            ResourceKey<Enchantment> key = (ResourceKey<Enchantment>) enchantments[i];
            stack.enchant(registry.getOrThrow(key), (Integer) enchantments[i + 1]);
        }
        return stack;
    }

    // Whether this player is somewhere the boss will fight them. Each boss narrows it to its lair.
    protected boolean canFight(Player player) {
        return true;
    }

    // Anything alive that it is touching takes its contact damage and is thrown clear.
    private void touch(ServerLevel level) {
        List<LivingEntity> touching = level.getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(0.1),
                entity -> entity != this && entity.isAlive() && !(entity instanceof TerrariaBoss) && !isMinion(entity)
                        && !(entity instanceof Player player && (player.isCreative() || player.isSpectator())));
        DamageSource source = this.damageSources().mobAttack(this);
        float damage = this.scaled(this.contactDamage());
        for (LivingEntity victim : touching) {
            if (victim.hurtServer(level, source, damage)) {
                Vec3 away = victim.position().subtract(this.position());
                victim.knockback(0.8, -away.x, -away.z, source, damage);
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
        // Going out with a bang: bursts all over it and a spray in its own colour.
        AABB box = this.getBoundingBox();
        Vec3 centre = box.getCenter();
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, centre.x, centre.y, centre.z, 3,
                box.getXsize() / 3, box.getYsize() / 3, box.getZsize() / 3, 0.0);
        level.sendParticles(new DustParticleOptions(this.kind().colour() & 0xFFFFFF, 3.0F), centre.x, centre.y, centre.z, 150,
                box.getXsize() / 2, box.getYsize() / 2, box.getZsize() / 2, 0.0);
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, centre.x, centre.y, centre.z, 80, 0.5, 0.5, 0.5, 0.6);
        level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 4.0F, 0.7F);
        level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.HOSTILE, 1.0F, 1.0F);
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
        if (attacker instanceof TerrariaBoss || attacker != null && isMinion(attacker)) {
            return false;
        }
        return super.hurtServer(level, source, damage);
    }
}
