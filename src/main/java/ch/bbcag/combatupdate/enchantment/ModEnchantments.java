package ch.bbcag.combatupdate.enchantment;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.enchantment.Enchantment;

import ch.bbcag.combatupdate.CombatUpdate;

// Enchantments are data-driven; their definitions live in data/combatupdate/enchantment/*.json.
// These keys just let code reference them (e.g. to check an item's enchantment level).
public class ModEnchantments {
    public static final ResourceKey<Enchantment> SHORTBOW = ResourceKey.create(
            Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "shortbow"));
}
