package ch.bbcag.combatupdate;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import ch.bbcag.combatupdate.entity.GipfaeliSoldier;

// The Gipfaeli command flag: the one item the army is run from.
//
// Three things on the one button, told apart by what the click landed on and whether the player is
// sneaking - the same trick the launcher and the rig use, for the same reason: a held item has only
// the one button, and an army needs more than one order.
//
//   right-click nothing   raise a new squad: its commander appears, and is clicked to fill it
//   right-click something send the squad after it
//   sneak + right-click   open the list of everything on the map worth sending them after
//
// Everything here happens on the server. The flag only ever decides which of GipfaeliArmy's orders
// this press meant.
public final class GipfaeliCommandFlag {
    // Long enough to outlast the repeat rate of a held right-click, so one press is one order
    // rather than a recruit a tick for as long as the button is down.
    private static final int CLICK_COOLDOWN_TICKS = 10;

    private GipfaeliCommandFlag() {
    }

    // Returns whether the flag took the click, which is the caller's cue to cancel the interaction
    // so nothing else acts on the same press.
    public static boolean use(Player player, ItemStack stack, Level level) {
        if (!holding(stack) || player.getCooldowns().isOnCooldown(stack)) {
            return false;
        }

        if (level instanceof ServerLevel && player instanceof ServerPlayer commander) {
            if (commander.isShiftKeyDown()) {
                GipfaeliArmy.openArmy(commander, GipfaeliArmy.Scope.ALL);
            } else {
                GipfaeliArmy.raiseSquad(commander);
            }
        }

        player.getCooldowns().addCooldown(stack, CLICK_COOLDOWN_TICKS);
        return true;
    }

    // The same press with something under the crosshair: pointing at a target is the plainest way
    // there is to name one, and it costs neither a menu nor a command.
    public static boolean useOn(Player player, ItemStack stack, Entity target, Level level) {
        if (!holding(stack) || player.getCooldowns().isOnCooldown(stack)) {
            return false;
        }

        if (level instanceof ServerLevel && player instanceof ServerPlayer commander) {
            if (commander.isShiftKeyDown()) {
                GipfaeliArmy.openArmy(commander, GipfaeliArmy.Scope.ALL);
            } else if (target instanceof GipfaeliSoldier soldier && soldier.isOwnedBy(commander)) {
                // One of ours. Pointing the squad at itself is never what was meant, so this press
                // opens the one soldier's own menu instead: its kit, its armour, its colour.
                GipfaeliArmy.click(commander, soldier);
            } else if (target instanceof LivingEntity victim) {
                GipfaeliArmy.attack(commander, GipfaeliArmy.Scope.ALL, victim);
            }
        }

        player.getCooldowns().addCooldown(stack, CLICK_COOLDOWN_TICKS);
        return true;
    }

    private static boolean holding(ItemStack stack) {
        return Config.on(Config.ENABLE_GIPFAELI_ARMY) && stack.is(CombatUpdate.GIPFAELI_COMMAND_FLAG.get());
    }
}
