package ch.bbcag.combatupdate;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// An elytra worked into a chestplate, so the chest slot no longer has to choose between armour and
// wings.
//
// No new item and no new slot: the game already lets anything equipped glide if it carries the
// minecraft:glider component (see LivingEntity#canGlideUsing), so the recipe just stamps that
// component onto the chestplate the player brought. Everything already on it - enchantments, its
// remaining durability, its trim - rides along untouched.
//
// What it costs is speed, because a chestplate is not a wing, and how much depends on what the
// chestplate is made of. That is read off item tags rather than a hardcoded list, so armour from
// other mods can be sorted into the same buckets from a datapack.
public final class ElytraFusion {
    // Gold by default: soft and light, so it barely spoils the airflow.
    public static final TagKey<Item> LIGHT = tag("fused_elytra/light");

    // Diamond and netherite by default: the plate that protects best also flies worst.
    public static final TagKey<Item> HEAVY = tag("fused_elytra/heavy");

    private ElytraFusion() {
    }

    // The fraction of glide speed the worn chestplate costs, or zero when the glide is coming from a
    // real elytra and nothing has been fused at all.
    public static double speedPenalty(LivingEntity entity) {
        ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.is(Items.ELYTRA) || !chest.has(DataComponents.GLIDER)) {
            return 0.0;
        }

        int percent = chest.is(LIGHT) ? Config.FUSED_ELYTRA_PENALTY_LIGHT.getAsInt()
                : chest.is(HEAVY) ? Config.FUSED_ELYTRA_PENALTY_HEAVY.getAsInt()
                : Config.FUSED_ELYTRA_PENALTY_NORMAL.getAsInt();
        return percent / 100.0;
    }

    private static TagKey<Item> tag(String path) {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(CombatUpdate.MODID, path));
    }
}
