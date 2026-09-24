package ch.bbcag.gipfeliarmy.client;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import ch.bbcag.gipfeliarmy.GipfaeliWeapon;

// How a Gipfaeli gun is held. Vanilla poses an unknown item the way it poses a stick - one arm out,
// the thing dangling off the end of it - which is no way to carry a rifle. A gun goes up to the
// shoulder in both hands, which is the crossbow's own hold and already points where the head is
// looking; the banner, which has nothing to aim, is simply carried.
//
// One answer for both kinds of hand that hold one: the player's, through the item extension the
// avatar renderer consults, and the soldier's, through its renderer (see GipfaeliSoldierRenderer).
public final class GipfaeliGunPose implements IClientItemExtensions {
    public static final GipfaeliGunPose INSTANCE = new GipfaeliGunPose();

    private GipfaeliGunPose() {
    }

    // The pose an arm takes for what it is holding, or null for something of nobody's business here.
    public static HumanoidModel.@Nullable ArmPose poseFor(ItemStack stack) {
        GipfaeliWeapon weapon = GipfaeliWeapon.of(stack);
        if (weapon == null) {
            return null;
        }

        return weapon.fires() ? HumanoidModel.ArmPose.CROSSBOW_HOLD : HumanoidModel.ArmPose.ITEM;
    }

    @Override
    public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
        HumanoidModel.ArmPose pose = poseFor(stack);
        return pose == null ? IClientItemExtensions.super.getArmPose(entity, hand, stack) : pose;
    }
}
