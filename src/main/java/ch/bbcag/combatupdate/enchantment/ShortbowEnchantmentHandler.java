package ch.bbcag.combatupdate.enchantment;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.ArrowLooseEvent;

import ch.bbcag.combatupdate.Config;

// Handles the Shortbow enchantment: releasing a bow fires as if it had been fully drawn,
// then goes on a short cooldown during which it draws normally instead of instantly.
public final class ShortbowEnchantmentHandler {
    // A charge of 20 ticks (BowItem.MAX_DRAW_DURATION) already yields full power, see BowItem#getPowerForTime.
    private static final int FULL_CHARGE_TICKS = 20;

    private ShortbowEnchantmentHandler() {
    }

    @SubscribeEvent
    public static void onArrowLoose(ArrowLooseEvent event) {
        ItemStack bow = event.getBow();
        Level level = event.getLevel();

        if (!Config.on(Config.ENABLE_SHORTBOW) || getShortbowLevel(bow, level) <= 0) {
            return;
        }

        Player player = event.getEntity();
        if (player.getCooldowns().isOnCooldown(bow)) {
            // Still on cooldown: let the shot fire at whatever charge was actually reached.
            return;
        }

        event.setCharge(FULL_CHARGE_TICKS);
        player.getCooldowns().addCooldown(bow, Config.SHORTBOW_COOLDOWN_TICKS.getAsInt());
    }

    private static int getShortbowLevel(ItemStack bow, Level level) {
        return level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .get(ModEnchantments.SHORTBOW)
                .map(bow::getEnchantmentLevel)
                .orElse(0);
    }
}
