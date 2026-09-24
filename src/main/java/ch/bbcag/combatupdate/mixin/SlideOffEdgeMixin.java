package ch.bbcag.combatupdate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import ch.bbcag.combatupdate.Movement;
import net.minecraft.world.entity.player.Player;

// Vanilla keeps a sneaking player from walking off an edge, and a slide is sneak held down, so
// without this a slide stops dead at the top of every step down a hill. On both sides, since the
// server replays the player's moves and would otherwise stop them at the edge it thinks they are on.
@Mixin(Player.class)
public abstract class SlideOffEdgeMixin {
    @Inject(method = "isStayingOnGroundSurface", at = @At("HEAD"), cancellable = true)
    private void combatupdate$slideOffEdge(CallbackInfoReturnable<Boolean> cir) {
        if (Movement.isSliding((Player) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
