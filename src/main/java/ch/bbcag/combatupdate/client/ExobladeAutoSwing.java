package ch.bbcag.combatupdate.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.HitResult;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.Config;
import ch.bbcag.combatupdate.Zenith;
import ch.bbcag.combatupdate.mixin.MinecraftAttackInvoker;

// Holding the attack key with the Exoblade keeps it swinging, a swing each time the strength meter
// fills, the way Terraria's auto-reuse swords swing while the button is down. Every one of those
// swings is a full-strength one, so every one throws an Exobeam. The Zenith swings the same way, and
// throws its phantoms on each.
//
// Not while pointing at a block: there a held key means mining, and vanilla already handles that.
public final class ExobladeAutoSwing {
    private ExobladeAutoSwing() {
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null
                || minecraft.gui.screen() != null
                || !minecraft.options.keyAttack.isDown()
                || !(Config.on(Config.ENABLE_EXOBLADE) && player.getMainHandItem().is(CombatUpdate.EXOBLADE.get())
                        || Zenith.wielding(player))
                || player.isUsingItem()
                || player.getAttackStrengthScale(0.0F) < 1.0F
                || minecraft.hitResult == null
                || minecraft.hitResult.getType() == HitResult.Type.BLOCK) {
            return;
        }

        ((MinecraftAttackInvoker) minecraft).combatupdate$startAttack();
    }
}
