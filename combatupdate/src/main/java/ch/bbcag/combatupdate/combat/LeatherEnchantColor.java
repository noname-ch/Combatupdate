package ch.bbcag.combatupdate.combat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;

import ch.bbcag.combatupdate.Config;
import ch.bbcag.combatupdate.enchantment.ModEnchantments;

// Recolors leather leggings/boots to reflect which combat enchantment they carry (Hypixel Pit style):
// Regularity pants go "rage" colored, Singularity pants go blue, Armageddon boots go dark red.
public class LeatherEnchantColor {
    public static void tick(Player player) {
        if (!Config.on(Config.ENABLE_LEATHER_ENCHANT_COLORS)) {
            return;
        }

        colorIfNeeded(player.getItemBySlot(EquipmentSlot.LEGS), Items.LEATHER_LEGGINGS, legsColor(player));
        colorIfNeeded(player.getItemBySlot(EquipmentSlot.FEET), Items.LEATHER_BOOTS, feetColor(player));
    }

    // Regularity takes priority if a pair of leggings somehow carries both enchantments at once.
    private static int legsColor(Player player) {
        if (ModEnchantments.getLevel(ModEnchantments.REGULARITY, player) > 0) {
            return Config.RAGE_PANTS_COLOR.getAsInt();
        }
        if (ModEnchantments.getLevel(ModEnchantments.SINGULARITY, player) > 0) {
            return Config.SINGULARITY_PANTS_COLOR.getAsInt();
        }
        return -1;
    }

    private static int feetColor(Player player) {
        if (ModEnchantments.getLevel(ModEnchantments.ARMAGEDDON, player) > 0) {
            return Config.ARMAGEDDON_BOOTS_COLOR.getAsInt();
        }
        return -1;
    }

    private static void colorIfNeeded(ItemStack stack, Item item, int desiredColor) {
        if (desiredColor < 0 || !stack.is(item)) {
            return;
        }

        DyedItemColor current = stack.get(DataComponents.DYED_COLOR);
        if (current != null && current.rgb() == desiredColor) {
            return;
        }

        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(desiredColor));
    }
}
