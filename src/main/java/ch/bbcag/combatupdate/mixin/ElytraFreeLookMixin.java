package ch.bbcag.combatupdate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ch.bbcag.combatupdate.client.ElytraOrientation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

// Mouse look while gliding is routed through a real 3D orientation (see ElytraOrientation) instead of
// vanilla's yaw/pitch arithmetic, so the controls stay aligned with the view at any attitude, and the
// nose can go over the top and round without hitting vanilla's +-90 degree pitch limit.
//
// Entity#turn has no event or overridable hook, so it is intercepted at the head and cancelled for the
// one case we care about. Everything that is not the local player gliding falls straight through to
// the vanilla method, which also leaves the method intact for other mods to work with.
@Mixin(Entity.class)
public abstract class ElytraFreeLookMixin {

    @Inject(method = "turn(DD)V", at = @At("HEAD"), cancellable = true)
    private void combatupdate$freeLookWhileGliding(double xo, double yo, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;

        if (self != Minecraft.getInstance().player || !(self instanceof LivingEntity living) || !living.isFallFlying()) {
            return;
        }

        ElytraOrientation.ensureActive(living);
        ElytraOrientation.applyMouse(xo, yo);
        ElytraOrientation.writeRotation(self);

        // Vanilla's tail end, which the cancellation would otherwise skip.
        if (self.getVehicle() != null) {
            self.getVehicle().onPassengerTurned(self);
        }

        ci.cancel();
    }
}
