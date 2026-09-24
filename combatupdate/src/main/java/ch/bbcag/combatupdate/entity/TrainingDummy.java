package ch.bbcag.combatupdate.entity;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;

import ch.bbcag.combatupdate.CombatUpdate;

// Something to hit that stays put and never dies: set one down, try a weapon on it, and read what
// each hit did off the damage numbers.
//
// It never moves. Knockback resistance takes care of ordinary hits and explosions, but a fair few
// of this mod's own weapons shove what they hit by setting its motion directly (the Battering Ram,
// the Exoblade's lunge), so any sideways or upward motion - water currents included - is also
// thrown away before the dummy gets to act on it. It can still fall, so one left standing on a block that is then mined out
// does not hang in the air.
//
// It never dies. Every hit goes through the whole damage pipeline - armour, enchantments, damage
// events - exactly as it would on a mob, and only then is the health put back, so what the damage
// numbers show is what that hit would really have done. The exception is damage that is meant to
// get past everything: /kill and the void still remove it.
//
// It counts as a monster (Enemy), because that is what this mod's homing weapons and the army's
// target menu go after, and a dummy they ignored would be no use for testing them. Mobs that hunt
// monsters on their own - iron golems, mostly - are kept off it (see #onChangeTarget), or a dummy
// set down in a village would have a golem punching it forever.
public final class TrainingDummy extends Mob implements Enemy {
    public TrainingDummy(EntityType<? extends TrainingDummy> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0);
    }

    // --- Never dies ---

    @Override
    protected void actuallyHurt(ServerLevel level, DamageSource source, float damage) {
        super.actuallyHurt(level, source, damage);
        if (!source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            this.setHealth(this.getMaxHealth());
        }
    }

    // --- Never moves ---

    @Override
    public void aiStep() {
        Vec3 motion = this.getDeltaMovement();
        this.setDeltaMovement(0.0, Math.min(motion.y, 0.0), 0.0);
        super.aiStep();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(Entity entity) {
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    // Monster hunters leave it be. The exception is whatever this tag names: the Gipfaeli Army mod
    // puts its soldiers in it, since a soldier only goes for a dummy when it has been ordered to,
    // and testing the guns is a fair reason to order it. Another mod's mob can join the same way.
    public static final TagKey<EntityType<?>> MAY_TARGET = TagKey.create(Registries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "may_target_training_dummy"));

    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() instanceof TrainingDummy && !event.getEntity().is(MAY_TARGET)) {
            event.setCanceled(true);
        }
    }

    // --- Dressing it, and picking it back up ---

    // Armour held out goes on it, so the same weapon can be tried against different armour; whatever
    // it replaces comes back. Sneaking with an empty hand picks the dummy up, armour and all.
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        EquipmentSlot armourSlot = armourSlotOf(held);
        boolean pickUp = held.isEmpty() && player.isSecondaryUseActive();
        if (armourSlot == null && !pickUp) {
            return super.mobInteract(player, hand);
        }

        if (this.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (pickUp) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack worn = this.getItemBySlot(slot);
                if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR && !worn.isEmpty()) {
                    player.getInventory().placeItemBackInInventory(worn.copy(), Prediction.SERVER_ONLY);
                }
            }

            player.getInventory().placeItemBackInInventory(new ItemStack(CombatUpdate.TRAINING_DUMMY_ITEM.get()), Prediction.SERVER_ONLY);
            this.playSound(SoundEvents.ARMOR_STAND_BREAK, 1.0F, 1.0F);
            this.discard();
            return InteractionResult.SUCCESS;
        }

        ItemStack previous = this.getItemBySlot(armourSlot).copy();
        this.setItemSlot(armourSlot, held.copyWithCount(1));
        this.setGuaranteedDrop(armourSlot);
        held.consume(1, player);
        if (!previous.isEmpty()) {
            player.getInventory().placeItemBackInInventory(previous, Prediction.SERVER_ONLY);
        }

        this.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.0F, 1.0F);
        return InteractionResult.SUCCESS;
    }

    private static @Nullable EquipmentSlot armourSlotOf(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? equippable.slot() : null;
    }

    @Override
    public @Nullable ItemStack getPickResult() {
        return new ItemStack(CombatUpdate.TRAINING_DUMMY_ITEM.get());
    }

    // A thump of straw and wood rather than a mob's cry.
    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ARMOR_STAND_HIT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ARMOR_STAND_BREAK;
    }
}
