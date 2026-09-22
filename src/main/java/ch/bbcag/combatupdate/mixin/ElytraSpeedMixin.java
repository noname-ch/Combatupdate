package ch.bbcag.combatupdate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import ch.bbcag.combatupdate.Config;
import ch.bbcag.combatupdate.ElytraFusion;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// Scales how fast an elytra glides, via Config.ELYTRA_SPEED_MULTIPLIER.
//
// This thins out vanilla's horizontal drag rather than scaling the velocity directly. Gliding speed
// settles where the push from the wings balances that drag, so leaving proportionally more of the
// speed each tick raises the speed it settles at and still settles. Multiplying the velocity itself
// would compound every tick into runaway acceleration instead.
@Mixin(LivingEntity.class)
public abstract class ElytraSpeedMixin {

    // The horizontal drag vanilla applies at the end of updateFallFlyingMovement.
    private static final double VANILLA_DRAG = 0.99;

    @Inject(method = "updateFallFlyingMovement", at = @At("RETURN"), cancellable = true)
    private void combatupdate$applySpeedMultiplier(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        // An elytra fused into a chestplate flies worse than the real thing, by however much that
        // chestplate weighs; it folds in here because it is the same knob, only applied per wearer.
        double multiplier = Config.ELYTRA_SPEED_MULTIPLIER.getAsDouble()
                * (1.0 - ElytraFusion.speedPenalty((LivingEntity) (Object) this));
        if (multiplier == 1.0) {
            return;
        }

        double scale = (1.0 - (1.0 - VANILLA_DRAG) / multiplier) / VANILLA_DRAG;
        Vec3 result = cir.getReturnValue();
        cir.setReturnValue(new Vec3(result.x * scale, result.y, result.z * scale));
    }
}
