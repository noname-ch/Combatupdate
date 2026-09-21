package ch.bbcag.combatupdate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import ch.bbcag.combatupdate.client.ElytraOrientation;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

// Mouse look while gliding is routed through a real 3D orientation (see ElytraOrientation) instead of
// vanilla's yaw/pitch arithmetic, so the controls stay aligned with the view at any attitude, and the
// nose can go over the top and round without hitting vanilla's +-90 degree pitch limit.
//
// Entity#turn has no event or overridable hook, so it is replaced outright; everything that is not the
// local player gliding falls through to the vanilla behaviour below, unchanged.
@Mixin(Entity.class)
public abstract class ElytraFreeLookMixin {

    @Overwrite
    public void turn(double xo, double yo) {
        Entity self = (Entity) (Object) this;

        if (self == Minecraft.getInstance().player && self instanceof LivingEntity living && living.isFallFlying()) {
            ElytraOrientation.ensureActive(living);
            ElytraOrientation.applyMouse(xo, yo);
            ElytraOrientation.writeRotation(self);
        } else {
            float xDelta = (float) yo * 0.15F;
            float yDelta = (float) xo * 0.15F;
            self.setXRot(self.getXRot() + xDelta);
            self.setYRot(self.getYRot() + yDelta);
            self.setXRot(Mth.clamp(self.getXRot(), -90.0F, 90.0F));
            self.xRotO += xDelta;
            self.yRotO += yDelta;
            self.xRotO = Mth.clamp(self.xRotO, -90.0F, 90.0F);
        }

        if (self.getVehicle() != null) {
            self.getVehicle().onPassengerTurned(self);
        }
    }
}
