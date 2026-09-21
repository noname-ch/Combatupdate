package ch.bbcag.combatupdate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

// Vanilla's Entity#turn hard-clamps pitch to [-90, 90] with no event or overridable method to hook into,
// so the only way to lift that clamp is to replace the method's body directly.
// While gliding with an elytra, the clamp is skipped entirely, letting the camera spin a full 360 degrees
// (e.g. to loop upside down or look straight behind you), matching what the "Pitchy" mod does.
@Mixin(Entity.class)
public abstract class ElytraFreeLookMixin {

    @Overwrite
    public void turn(double xo, double yo) {
        Entity self = (Entity) (Object) this;
        float xDelta = (float) yo * 0.15F;
        float yDelta = (float) xo * 0.15F;

        self.setXRot(self.getXRot() + xDelta);
        self.setYRot(self.getYRot() + yDelta);
        self.xRotO += xDelta;
        self.yRotO += yDelta;

        boolean freeLook = self instanceof LivingEntity living && living.isFallFlying();
        if (!freeLook) {
            self.setXRot(Mth.clamp(self.getXRot(), -90.0F, 90.0F));
            self.xRotO = Mth.clamp(self.xRotO, -90.0F, 90.0F);
        }

        if (self.getVehicle() != null) {
            self.getVehicle().onPassengerTurned(self);
        }
    }
}
