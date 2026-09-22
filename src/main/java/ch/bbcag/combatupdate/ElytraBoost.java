package ch.bbcag.combatupdate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

// Holding shift while gliding burns firework rockets straight out of the inventory for a continuous
// boost, instead of having to hold and right-click them one at a time.
//
// Deliberately run on both sides from the same tick event: the client knows its own sneak state and
// its own inventory, so it reaches the same answer the server does and predicts the acceleration
// itself. Driving it from the server alone would mean every boost arrived as a correction, which
// reads as rubber-banding. Only the server actually spends the rocket and plays the sound.
public final class ElytraBoost {
    // Only holds players who are boosting right now, so it empties itself as soon as they stop.
    //
    // Kept per side: in single player both the client and the server player tick in the same JVM under
    // the same UUID, so a single map would have them count each other's ticks and spend rockets at
    // roughly half the intended interval.
    private static final Map<UUID, Integer> CLIENT_BOOST_TICKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SERVER_BOOST_TICKS = new ConcurrentHashMap<>();

    private static final int NO_ROCKET = -1;

    private ElytraBoost() {
    }

    public static void tick(Player player) {
        boolean clientSide = player.level().isClientSide();

        // On the client only our own player is predicted; everyone else's motion arrives from the
        // server, and boosting them here would only fight those updates.
        if (clientSide && !player.isLocalPlayer()) {
            return;
        }

        Map<UUID, Integer> boostTicks = clientSide ? CLIENT_BOOST_TICKS : SERVER_BOOST_TICKS;
        if (!player.isFallFlying() || !player.isShiftKeyDown()) {
            boostTicks.remove(player.getUUID());
            return;
        }

        // Creative flight pays for nothing, so it never needs a rocket in the first place.
        boolean free = player.getAbilities().instabuild;
        int rocketSlot = free ? NO_ROCKET : findRocketSlot(player);
        if (!free && rocketSlot == NO_ROCKET) {
            boostTicks.remove(player.getUUID());
            return;
        }

        int boostedTicks = boostTicks.merge(player.getUUID(), 1, Integer::sum);

        // Same shape as the push a firework rocket gives: ease the current velocity towards 1.5
        // blocks/tick along the look direction rather than adding to it without limit.
        Vec3 look = player.getLookAngle();
        Vec3 delta = player.getDeltaMovement();
        player.setDeltaMovement(delta.add(
                look.x * 0.1 + (look.x * 1.5 - delta.x) * 0.5,
                look.y * 0.1 + (look.y * 1.5 - delta.y) * 0.5,
                look.z * 0.1 + (look.z * 1.5 - delta.z) * 0.5));

        // Charged on the first tick of a boost, so a brief tap still costs one rocket.
        // Server-side only: the sound is broadcast from there to everyone nearby, the boosting player
        // included, so playing it on the client as well would just double it up.
        if (!clientSide && (boostedTicks - 1) % Config.BOOST_ROCKET_INTERVAL_TICKS.getAsInt() == 0) {
            if (!free) {
                player.getInventory().getItem(rocketSlot).shrink(1);
            }

            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    }

    private static int findRocketSlot(Player player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(Items.FIREWORK_ROCKET)) {
                return slot;
            }
        }

        return NO_ROCKET;
    }
}
