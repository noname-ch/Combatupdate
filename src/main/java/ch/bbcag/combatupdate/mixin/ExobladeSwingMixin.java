package ch.bbcag.combatupdate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ch.bbcag.combatupdate.Exoblade;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

// The Exoblade throws a beam on every well-timed swing, including one at thin air. A swing that hits
// nothing reaches the server only as this packet, and there is no event for it.
//
// Hooked just before the strength meter is reset rather than at the head: the head still runs on the
// network thread, before the packet has been handed over to the server thread, and by the reset the
// meter would already read empty.
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ExobladeSwingMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handlePunch", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;resetAttackStrengthTicker()V"))
    private void combatupdate$exobladeSwing(ServerboundPunchPacket packet, CallbackInfo ci) {
        Exoblade.onSwing(this.player);
    }
}
