package ch.bbcag.combatupdate.entity;

import java.util.EnumSet;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.Config;
import ch.bbcag.combatupdate.GipfaeliArmy;
import ch.bbcag.combatupdate.GipfaeliWeapon;

// A soldier of the Gipfaeli army: recruited with the command flag (see GipfaeliArmy), armed with
// whatever gun it was handed, and pointed at things by whoever recruited it.
//
// It is a TamableAnimal because almost everything an army has to do, a tamed wolf already does:
// know whose it is, follow that person about, teleport to them when it falls behind, sit still when
// told, and take on whatever hurt them. What is added here is the part a wolf has no use for - a
// target its owner picked out for it, and a gun to answer that with.
//
// Nothing about it is stored on the client. What the client is told is what it is told about any
// other mob, plus the item in its hand, which is also the only record of which weapon it carries.
public final class GipfaeliSoldier extends TamableAnimal implements RangedAttackMob {
    // How long a soldier that has lost sight of its mark keeps hunting before it gives up and falls
    // back in with the commander.
    private static final int GIVE_UP_TICKS = 200;

    // Ordered about, a soldier will walk away from its commander; this is how far it strays looking
    // for one step of the way there before the path is worked out again.
    private static final int MARCH_STEP = 12;

    private static final int REPATH_INTERVAL_TICKS = 10;
    private static final int MELEE_COOLDOWN_TICKS = 20;

    // What the commander last pointed this one at, held as a UUID so it survives being written out
    // and read back. It outlives the mob's own target: a soldier that loses sight of its mark still
    // knows what it was sent after.
    private @Nullable UUID orderedTargetId;

    public GipfaeliSoldier(EntityType<? extends GipfaeliSoldier> type, Level level) {
        super(type, level);
        // Recruited, not spawned. Nothing about a soldier should be cleaned up because the
        // commander walked far enough away from it.
        this.setPersistenceRequired();
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return TamableAnimal.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.31)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.ARMOR, 4.0)
                // What a soldier notices on its own, and the box it sweeps on a timer to do it.
                // Kept to a zombie's reach rather than a marksman's, because eight soldiers each
                // sweeping the ground a rifle can cover is the expensive way to stand guard. An
                // order reaches far past this; see OrderedTargetGoal.
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new FloatGoal(this));
        // Told to hold, a soldier holds - this is the goal that plants it, and the same flag stops
        // FollowOwnerGoal below from pulling it back in (TamableAnimal#unableToMoveToOwner).
        this.goalSelector.addGoal(2, new SitWhenOrderedToGoal(this));
        this.goalSelector.addGoal(3, new GunfightGoal(this));
        this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.2, true));
        this.goalSelector.addGoal(5, new FollowOwnerGoal(this, 1.15, 8.0F, 3.0F));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 10.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        // First, above everything reactive: an order is an order, and it outranks whatever the
        // soldier would have picked for itself.
        this.targetSelector.addGoal(1, new OrderedTargetGoal(this));
        this.targetSelector.addGoal(2, new OwnerHurtByTargetGoal(this));
        this.targetSelector.addGoal(3, new OwnerHurtTargetGoal(this));
        this.targetSelector.addGoal(4, new HurtByTargetGoal(this));
        // Standing guard. Off by config for anyone who wants a squad that only ever shoots what it
        // was told to; the predicate is read per candidate, so the switch takes effect at once.
        this.targetSelector.addGoal(5, new NearestAttackableTargetGoal<>(this, Monster.class, true,
                (target, level) -> Config.ARMY_GUARDS.get() && !GipfaeliArmy.sameSide(this, target)));
    }

    // The gun in its hand, which is the only record of what it is carrying: hand it a different one
    // and it fights differently from the next tick on.
    public @Nullable GipfaeliWeapon weapon() {
        return GipfaeliWeapon.of(this.getMainHandItem());
    }

    public void arm(ItemStack weapon) {
        this.setItemSlot(EquipmentSlot.MAINHAND, weapon.copyWithCount(1));
    }

    // Points this soldier at something, or lets it stand down when handed null.
    public void order(@Nullable LivingEntity target) {
        this.orderedTargetId = target == null ? null : target.getUUID();
        if (target == null && this.getTarget() != null) {
            this.setTarget(null);
        }
    }

    // What it was last pointed at, if that is still alive and still in this world. Deliberately not
    // range-checked: something that has run out of reach has not stopped being the order, and a
    // soldier that walks back into range takes it up again where it left off.
    public @Nullable LivingEntity orderedTarget() {
        if (this.orderedTargetId == null || !(this.level() instanceof ServerLevel level)) {
            return null;
        }

        return level.getEntity(this.orderedTargetId) instanceof LivingEntity target && target.isAlive()
                ? target
                : null;
    }

    public boolean holdingPosition() {
        return this.isOrderedToSit();
    }

    // Holding position and following are the same switch seen from either side, and both are the
    // sitting flag underneath: a soldier told to hold is a dog told to stay.
    public void holdPosition(boolean hold) {
        this.setOrderedToSit(hold);
        this.setInSittingPose(hold);
        if (hold) {
            this.getNavigation().stop();
        }
    }

    @Override
    public void performRangedAttack(LivingEntity target, float power) {
        GipfaeliWeapon weapon = this.weapon();
        if (weapon == null || !(this.level() instanceof ServerLevel level)) {
            return;
        }

        // Aimed at the middle of the target rather than its feet, the same way the launcher's
        // homing does, so a shot at anything tall does not bury itself in the ground short of it.
        Vec3 direction = target.getBoundingBox().getCenter().subtract(this.getEyePosition());
        weapon.fire(level, this, direction, target);
        this.swingForAttack(InteractionHand.MAIN_HAND);
    }

    // Right-clicking one of your own: a gun in your hand swaps its weapon over, an empty hand tells
    // it to hold or to fall back in, and a Gipfaeli feeds it.
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.isOwnedBy(player)) {
            return super.mobInteract(player, hand);
        }

        ItemStack held = player.getItemInHand(hand);
        if (this.level().isClientSide()) {
            boolean handled = GipfaeliWeapon.of(held) != null
                    || held.isEmpty()
                    || held.is(CombatUpdate.GIPFAELI.get());
            return handled ? InteractionResult.SUCCESS : super.mobInteract(player, hand);
        }

        GipfaeliWeapon offered = GipfaeliWeapon.of(held);
        if (offered != null) {
            ItemStack previous = this.getMainHandItem().copy();
            this.arm(held);
            held.shrink(1);
            if (!previous.isEmpty()) {
                player.getInventory().placeItemBackInInventory(previous, Prediction.SERVER_ONLY);
            }

            this.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.0F, 1.0F);
            GipfaeliArmy.readout(player, Component.translatable("combatupdate.army.rearmed", this.getMainHandItem().getHoverName()));
            return InteractionResult.SUCCESS;
        }

        // Rations. A Gipfaeli is what the squad runs on, so it is also what patches one up.
        if (held.is(CombatUpdate.GIPFAELI.get()) && this.getHealth() < this.getMaxHealth()) {
            this.heal(4.0F);
            held.consume(1, player);
            this.playSound(SoundEvents.GENERIC_EAT.value(), 1.0F, 1.0F);
            return InteractionResult.SUCCESS;
        }

        if (held.isEmpty()) {
            this.holdPosition(!this.holdingPosition());
            GipfaeliArmy.readout(player, Component.translatable(this.holdingPosition()
                    ? "combatupdate.army.one.holding"
                    : "combatupdate.army.one.following"));
            return InteractionResult.SUCCESS;
        }

        return super.mobInteract(player, hand);
    }

    // A soldier carries its gun out of the world with it unless something kills it, in which case
    // whatever is left of the gun is there on the ground to be picked up. Dismissing one hands it
    // back properly (see GipfaeliArmy), so this is only ever the death case.
    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
        super.dropCustomDeathLoot(level, source, killedByPlayer);
        ItemStack weapon = this.getMainHandItem();
        if (!weapon.isEmpty() && Config.ARMY_CONSUMES_SUPPLIES.get()) {
            this.spawnAtLocation(level, weapon.copy());
        }

        this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    // Never on its own side, whatever it was told. Without this an order aimed at a crowd takes the
    // squad's own soldiers with it, and OwnerHurtTargetGoal turns the squad on its commander the
    // first time they hit one of their own by accident.
    @Override
    public boolean canAttack(LivingEntity target) {
        return !GipfaeliArmy.sameSide(this, target) && super.canAttack(target);
    }

    @Override
    public boolean wantsToAttack(LivingEntity target, LivingEntity owner) {
        return !GipfaeliArmy.sameSide(this, target);
    }

    // A soldier is shaped like a person and takes a hit like one, so it says so. There is no
    // ambient sound on purpose: eight of anything muttering to itself behind you is a lot.
    @Override
    protected @Nullable SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected @Nullable SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    @Override
    public boolean isFood(ItemStack itemStack) {
        // Gipfaeli are rations, handled in mobInteract above. Saying yes here would put the squad
        // in the mood to breed instead, which is not what an army is for.
        return false;
    }

    @Override
    public @Nullable AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (this.orderedTargetId != null) {
            output.store("OrderedTarget", UUIDUtil.CODEC, this.orderedTargetId);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.orderedTargetId = input.read("OrderedTarget", UUIDUtil.CODEC).orElse(null);
    }

    // Takes the target the commander picked and hands it to the mob as its own, for as long as it
    // is alive and within reach. Written out rather than built on TargetGoal because that one drops
    // anything past the follow range attribute, and the whole point of an order is that it can be
    // called on something across the field.
    private static final class OrderedTargetGoal extends Goal {
        private final GipfaeliSoldier soldier;

        OrderedTargetGoal(GipfaeliSoldier soldier) {
            this.soldier = soldier;
            this.setFlags(EnumSet.of(Goal.Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            LivingEntity ordered = this.soldier.orderedTarget();
            return ordered != null
                    && ordered.distanceTo(this.soldier) <= Config.ARMY_MARCH_RANGE.getAsDouble()
                    && this.soldier.canAttack(ordered);
        }

        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        @Override
        public void start() {
            this.soldier.setTarget(this.soldier.orderedTarget());
        }

        @Override
        public void tick() {
            LivingEntity ordered = this.soldier.orderedTarget();
            if (ordered != null && this.soldier.getTarget() != ordered) {
                this.soldier.setTarget(ordered);
            }
        }

        @Override
        public void stop() {
            // The order is over rather than merely unreachable when the thing it named is gone for
            // good; a target only out of range keeps its order and gets marched on again later.
            if (this.soldier.orderedTargetId != null && this.soldier.orderedTarget() == null) {
                this.soldier.order(null);
            }
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }
    }

    // Fighting with a gun: close to where the weapon is worth firing, keep the target in view, and
    // pull the trigger whenever the weapon is ready. One goal covers all four weapons, because what
    // separates them is entirely in their numbers - how far they reach, how close their carrier
    // wants to be, and how long between shots.
    private static final class GunfightGoal extends Goal {
        private final GipfaeliSoldier soldier;

        private int triggerCooldown;
        private int meleeCooldown;
        private int repathCooldown;
        private int unseenTicks;

        GunfightGoal(GipfaeliSoldier soldier) {
            this.soldier = soldier;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.soldier.getTarget();
            return target != null && target.isAlive() && this.soldier.weapon() != null;
        }

        @Override
        public boolean canContinueToUse() {
            return this.canUse() && this.unseenTicks < GIVE_UP_TICKS;
        }

        @Override
        public void start() {
            this.unseenTicks = 0;
            this.repathCooldown = 0;
        }

        @Override
        public void stop() {
            this.soldier.getNavigation().stop();
            this.soldier.setTarget(null);
            this.unseenTicks = 0;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity target = this.soldier.getTarget();
            GipfaeliWeapon weapon = this.soldier.weapon();
            if (target == null || weapon == null) {
                return;
            }

            this.soldier.getLookControl().setLookAt(target, 30.0F, 30.0F);

            double distance = this.soldier.distanceTo(target);
            boolean inSight = this.soldier.getSensing().hasLineOfSight(target);
            this.unseenTicks = inSight ? 0 : this.unseenTicks + 1;

            this.move(target, weapon, distance, inSight);

            if (this.triggerCooldown > 0) {
                this.triggerCooldown--;
            } else if (inSight && distance <= weapon.range() && distance >= weapon.minimum()) {
                this.soldier.performRangedAttack(target, 1.0F);
                this.triggerCooldown = weapon.cooldownTicks();
            }

            // A gun is no use with something already on top of you, so at arm's length the soldier
            // stops shooting past it and uses the butt of the thing.
            if (this.meleeCooldown > 0) {
                this.meleeCooldown--;
            } else if (distance <= 2.0 && this.soldier.level() instanceof ServerLevel level) {
                this.soldier.swingForAttack(InteractionHand.MAIN_HAND);
                this.soldier.doHurtTarget(level, target);
                this.meleeCooldown = MELEE_COOLDOWN_TICKS;
            }
        }

        // Walking is worked out a few times a second rather than every tick: a path is expensive,
        // and a squad of eight recalculating one apiece every tick is the expensive part of having
        // a squad at all.
        private void move(LivingEntity target, GipfaeliWeapon weapon, double distance, boolean inSight) {
            if (this.repathCooldown > 0) {
                this.repathCooldown--;
                return;
            }

            this.repathCooldown = REPATH_INTERVAL_TICKS;

            // Too far to path to in one go: head a dozen blocks towards it and work the rest out
            // when we get there. This is what lets an order be called on something right across the
            // map rather than only on what the soldier could already walk to.
            if (distance > this.soldier.getAttributeValue(Attributes.FOLLOW_RANGE)) {
                Vec3 towards = target.position().subtract(this.soldier.position()).normalize().scale(MARCH_STEP);
                Vec3 step = this.soldier.position().add(towards);
                this.soldier.getNavigation().moveTo(step.x, step.y, step.z, 1.2);
                return;
            }

            if (distance > weapon.standoff() || !inSight) {
                this.soldier.getNavigation().moveTo(target, 1.1);
                return;
            }

            // Well inside the weapon's own reach with a clear shot: back off to where it shoots
            // best rather than crowding a target that is about to hit back.
            if (distance < weapon.standoff() * 0.5) {
                Vec3 away = DefaultRandomPos.getPosAway(this.soldier, 10, 5, target.position());
                if (away != null) {
                    this.soldier.getNavigation().moveTo(away.x, away.y, away.z, 1.0);
                    return;
                }
            }

            this.soldier.getNavigation().stop();
        }
    }
}
