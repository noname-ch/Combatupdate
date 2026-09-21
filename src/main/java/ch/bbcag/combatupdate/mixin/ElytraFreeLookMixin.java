package ch.bbcag.combatupdate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

// Vanilla clamps pitch to [-90, 90] in TWO places: Entity#turn (used for mouse look) AND
// Entity#setXRot itself (the field setter that turn() calls into). Overwriting only turn()
// is not enough, since setXRot() re-clamps unconditionally regardless of what turn() passes it -
// both need to skip the clamp while gliding with an elytra for the camera to actually spin freely.
// There's no event or overridable method for either, so both methods are replaced outright.
@Mixin(Entity.class)
public abstract class ElytraFreeLookMixin {

    @Shadow
    private float xRot;

    @Overwrite
    public void turn(double xo, double yo) {
        Entity self = (Entity) (Object) this;
        float xDelta = (float) yo * 0.15F;
        float yDelta = (float) xo * 0.15F;

        self.setXRot(self.getXRot() + xDelta);
        self.setYRot(self.getYRot() + yDelta);
        self.xRotO += xDelta;
        self.yRotO += yDelta;

        if (!(self instanceof LivingEntity living && living.isFallFlying())) {
            self.xRotO = Mth.clamp(self.xRotO, -90.0F, 90.0F);
        }

        if (self.getVehicle() != null) {
            self.getVehicle().onPassengerTurned(self);
        }
    }

    @Overwrite
    public void setXRot(float xRot) {
        if (!Float.isFinite(xRot)) {
            Util.logAndPauseIfInIde("Invalid entity rotation: " + xRot + ", discarding.");
            return;
        }

        Entity self = (Entity) (Object) this;
        if (self instanceof LivingEntity living && living.isFallFlying()) {
            this.xRot = xRot % 360.0F;
        } else {
            this.xRot = Mth.clamp(xRot % 360.0F, -90.0F, 90.0F);
        }
    }
}
