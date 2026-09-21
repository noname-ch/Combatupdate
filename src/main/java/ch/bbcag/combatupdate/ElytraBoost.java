package ch.bbcag.combatupdate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

// Holding shift while gliding burns firework rockets straight out of the inventory for a continuous
// boost, instead of having to hold and right-click them one at a time.
//
// Deliberately run on both sides from the same tick event: the client knows its own sneak state and
// its own inventory, so it reaches the same answer the server does and predicts the acceleration
// itself. Driving it from the server alone would mean every boost arrived as a correction, which
// reads as rubber-banding. Only the server actually spends the rocket.
public final class ElytraBoost {
    // Only holds players who are boosting right now, so it empties itself as soon as they stop.
    private static final Map<UUID, Integer> BOOST_TICKS = new ConcurrentHashMap<>();

    private ElytraBoost() {
    }

    public static void tick(Player player) {
        if (!player.isFallFlying() || !player.isShiftKeyDown() || !hasRocket(player)) {
            BOOST_TICKS.remove(player.getUUID());
            return;
        }

        int boostedTicks = BOOST_TICKS.merge(player.getUUID(), 1, Integer::sum);

        // Same shape as the push a firework rocket gives: ease the current velocity towards 1.5
        // blocks/tick along the look direction rather than adding to it without limit.
        Vec3 look = player.getLookAngle();
        Vec3 delta = player.getDeltaMovement();
        player.setDeltaMovement(delta.add(
                look.x * 0.1 + (look.x * 1.5 - delta.x) * 0.5,
                look.y * 0.1 + (look.y * 1.5 - delta.y) * 0.5,
                look.z * 0.1 + (look.z * 1.5 - delta.z) * 0.5));

        // Charged on the first tick of a boost, so a brief tap still costs one rocket.
        if ((boostedTicks - 1) % Config.BOOST_ROCKET_INTERVAL_TICKS.getAsInt() == 0) {
            if (!player.level().isClientSide()) {
                consumeRocket(player);
            }

            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    }

    private static boolean hasRocket(Player player) {
        if (player.getAbilities().instabuild) {
            return true;
        }

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(Items.FIREWORK_ROCKET)) {
                return true;
            }
        }

        return false;
    }

    private static void consumeRocket(Player player) {
        if (player.getAbilities().instabuild) {
            return;
        }

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.FIREWORK_ROCKET)) {
                stack.shrink(1);
                return;
            }
        }
    }
}
