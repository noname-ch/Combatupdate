package ch.bbcag.combatupdate;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.combatupdate.entity.GipfaeliRocket;

// The Gipfaeli launcher: a tube that throws a pastry hard enough to leave a crater.
//
// Two things on the one button, told apart by whether the player is sneaking, because a held item has
// only the one: sneak to work the sight (see GipfaeliLock), use it plainly to fire. A shot taken with
// something sighted chases it; a shot taken with nothing sighted flies straight.
public final class GipfaeliLauncher {
    private static final int NO_AMMO = -1;

    // Long enough to outlast the repeat rate of a held right-click, short enough that sighting and
    // then firing still feels like two halves of the one motion.
    private static final int SIGHT_COOLDOWN_TICKS = 10;

    private GipfaeliLauncher() {
    }

    // Returns whether the launcher took the click, which is the caller's cue to cancel the interaction
    // so nothing else acts on the same press.
    public static boolean use(Player player, ItemStack stack, Level level) {
        if (!Config.on(Config.ENABLE_GIPFAELI) || !stack.is(CombatUpdate.GIPFAELI_LAUNCHER.get())) {
            return false;
        }

        // The game re-runs a held right-click every few ticks, which without this would have the sight
        // flickering on and off several times a second for as long as the button was down.
        if (player.getCooldowns().isOnCooldown(stack)) {
            return false;
        }

        if (!player.isShiftKeyDown()) {
            return fire(player, stack, level);
        }

        boolean sighted = GipfaeliLock.sight(player);
        player.getCooldowns().addCooldown(stack, SIGHT_COOLDOWN_TICKS);
        return sighted;
    }

    private static boolean fire(Player player, ItemStack stack, Level level) {
        // Creative pays for nothing, and neither does anyone who has turned the ammo off.
        boolean free = player.getAbilities().instabuild || !Config.GIPFAELI_CONSUMES_AMMO.get();
        int ammoSlot = free ? NO_AMMO : findAmmoSlot(player);
        if (!free && ammoSlot == NO_AMMO) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.8F, 1.0F);
            GipfaeliLock.readout(player, Component.translatable("combatupdate.gipfaeli.empty"));
            return true;
        }

        if (level instanceof ServerLevel serverLevel) {
            Vec3 eyePosition = player.getEyePosition();
            Vec3 lookAngle = player.getLookAngle();

            LivingEntity target = GipfaeliLock.target(serverLevel, player);
            GipfaeliRocket rocket = new GipfaeliRocket(serverLevel, player, lookAngle, target);
            rocket.setPos(eyePosition.add(lookAngle));
            serverLevel.addFreshEntity(rocket);

            if (!free) {
                player.getInventory().getItem(ammoSlot).shrink(1);
            }
        }

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.0F, 0.6F);

        player.getCooldowns().addCooldown(stack, Config.GIPFAELI_COOLDOWN_TICKS.getAsInt());
        return true;
    }

    private static int findAmmoSlot(Player player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(CombatUpdate.GIPFAELI.get())) {
                return slot;
            }
        }

        return NO_AMMO;
    }
}
