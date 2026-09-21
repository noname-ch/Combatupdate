package ch.bbcag.combatupdate.enchantment;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.ArrowLooseEvent;

// Handles the Shortbow enchantment: releasing a bow always fires as if it had been
// fully drawn, gated by a short cooldown so it can't be spammed for full-power shots every tick.
public class ShortbowEnchantmentHandler {
    // A charge of 20 ticks (BowItem.MAX_DRAW_DURATION) already yields full power, see BowItem#getPowerForTime.
    private static final int FULL_CHARGE_TICKS = 20;
    private static final int COOLDOWN_TICKS = 20; // 1 second

    @SubscribeEvent
    public static void onArrowLoose(ArrowLooseEvent event) {
        ItemStack bow = event.getBow();
        Level level = event.getLevel();

        if (getShortbowLevel(bow, level) <= 0) {
            return;
        }

        Player player = event.getEntity();
        if (player.getCooldowns().isOnCooldown(bow)) {
            // Block the shot entirely instead of letting it fire at whatever charge was reached.
            event.setCharge(0);
            return;
        }

        event.setCharge(FULL_CHARGE_TICKS);
        player.getCooldowns().addCooldown(bow, COOLDOWN_TICKS);
    }

    private static int getShortbowLevel(ItemStack bow, Level level) {
        return level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .get(ModEnchantments.SHORTBOW)
                .map(bow::getEnchantmentLevel)
                .orElse(0);
    }
}
