package ch.bbcag.combatupdate.enchantment;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import ch.bbcag.combatupdate.CombatUpdate;

// Enchantments are data-driven; their definitions live in data/combatupdate/enchantment/*.json.
// These keys just let code reference them (e.g. to check an item's enchantment level).
public final class ModEnchantments {
    public static final ResourceKey<Enchantment> SHORTBOW = ResourceKey.create(
            Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "shortbow"));

    public static final ResourceKey<Enchantment> BATTERING_RAM = ResourceKey.create(
            Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "battering_ram"));

    public static final ResourceKey<Enchantment> REGULARITY = ResourceKey.create(
            Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "regularity"));
    public static final ResourceKey<Enchantment> SINGULARITY = ResourceKey.create(
            Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "singularity"));
    public static final ResourceKey<Enchantment> ARMAGEDDON = ResourceKey.create(
            Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "armageddon"));
    public static final ResourceKey<Enchantment> GOTTA_GO_FAST = ResourceKey.create(
            Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "gotta_go_fast"));
    public static final ResourceKey<Enchantment> LIFESTEAL = ResourceKey.create(
            Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "lifesteal"));
    public static final ResourceKey<Enchantment> COMBO_PERUN = ResourceKey.create(
            Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "combo_perun"));
    public static final ResourceKey<Enchantment> GAMBLE = ResourceKey.create(
            Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "gamble"));
    public static final ResourceKey<Enchantment> MIRROR = ResourceKey.create(
            Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "mirror"));

    private ModEnchantments() {
    }

    // Looks up an enchantment's level on whatever equipment slot(s) it declares support for
    // (e.g. mainhand for weapon enchants, legs/feet/armor for armor enchants).
    public static int getLevel(ResourceKey<Enchantment> key, LivingEntity entity) {
        return entity.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .get(key)
                .map(holder -> EnchantmentHelper.getEnchantmentLevel(holder, entity))
                .orElse(0);
    }
}
