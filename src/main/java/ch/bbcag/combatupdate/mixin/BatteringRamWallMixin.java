package ch.bbcag.combatupdate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ch.bbcag.combatupdate.enchantment.BatteringRam;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

// A Battering Ram helmet takes the wall so its wearer doesn't. Vanilla's fly-into-wall damage is
// skipped outright, and a hard enough hit answers with a wind burst instead - the helmet is the thing
// doing the hitting, so it is the thing that pays, in durability rather than in the player's health.
//
// handleFallFlyingCollisions is private and does nothing but work out that damage, so there is no
// hook to hang this on and the whole method is intercepted at the head. Anything not wearing the
// enchantment falls straight through to vanilla, untouched.
@Mixin(LivingEntity.class)
public abstract class BatteringRamWallMixin {

    @Inject(method = "handleFallFlyingCollisions", at = @At("HEAD"), cancellable = true)
    private void combatupdate$ramThroughWall(double moveHorLength, double newMoveHorLength, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof Player player) || BatteringRam.ramLevel(player) <= 0) {
            return;
        }

        ci.cancel();

        // Vanilla's own guard: the method is called every tick of a glide, but only a tick that ran
        // into something is an impact.
        if (self.horizontalCollision) {
            BatteringRam.onWallImpact(player, moveHorLength - newMoveHorLength, moveHorLength);
        }
    }
}
