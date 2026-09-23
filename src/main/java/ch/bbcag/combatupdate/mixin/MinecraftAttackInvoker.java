package ch.bbcag.combatupdate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.Minecraft;

// Vanilla only swings on a fresh press of the attack key. The Exoblade swings for as long as the key
// is held, as Terraria's auto-reuse weapons do (see ExobladeAutoSwing), and the one way to swing
// exactly as a click would is the click's own private method.
@Mixin(Minecraft.class)
public interface MinecraftAttackInvoker {
    @Invoker("startAttack")
    boolean combatupdate$startAttack();
}
