package ch.bbcag.combatupdate;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

// The suits of vanilla armour a soldier can be dressed in, as the soldier menu offers them: one
// button a material, four pieces a button. A soldier wears armour exactly the way a player does -
// the pieces sit in its equipment slots and vanilla adds the protection up - so all this is is the
// list of what goes with what.
public enum GipfaeliArmour {
    LEATHER(Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS),
    CHAINMAIL(Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS),
    IRON(Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS),
    GOLDEN(Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS),
    DIAMOND(Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS),
    NETHERITE(Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS);

    // The four slots a suit fills, in the order the pieces below are given.
    public static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static final GipfaeliArmour[] ALL = values();

    private final Item[] pieces;

    GipfaeliArmour(Item helmet, Item chestplate, Item leggings, Item boots) {
        this.pieces = new Item[] {helmet, chestplate, leggings, boots};
    }

    public static @Nullable GipfaeliArmour byName(String name) {
        for (GipfaeliArmour armour : ALL) {
            if (armour.token().equalsIgnoreCase(name)) {
                return armour;
            }
        }

        return null;
    }

    // Which suit a piece belongs to, or null for anything that is not one of these.
    public static @Nullable GipfaeliArmour of(Item item) {
        for (GipfaeliArmour armour : ALL) {
            for (Item piece : armour.pieces) {
                if (piece == item) {
                    return armour;
                }
            }
        }

        return null;
    }

    public Item piece(int index) {
        return this.pieces[index];
    }

    public String token() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return Component.translatable("combatupdate.army.armour." + this.token());
    }
}
