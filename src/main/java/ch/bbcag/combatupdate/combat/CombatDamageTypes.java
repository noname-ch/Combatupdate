package ch.bbcag.combatupdate.combat;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import ch.bbcag.combatupdate.CombatUpdate;

// Damage types used by the mod's combat enchantments. Both are data-driven (data/combatupdate/damage_type),
// but true_damage additionally carries the vanilla bypasses_armor/bypasses_enchantments tags (see
// data/minecraft/tags/damage_type) so it ignores armor and protection-style enchants; only Mirror can stop it.
public class CombatDamageTypes {
    public static final ResourceKey<DamageType> TRUE_DAMAGE = ResourceKey.create(
            Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "true_damage"));
    public static final ResourceKey<DamageType> REGULARITY_STRIKE = ResourceKey.create(
            Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "regularity_strike"));

    public static DamageSource trueDamage(Level level, LivingEntity attacker) {
        return source(level, TRUE_DAMAGE, attacker);
    }

    public static DamageSource regularityStrike(Level level, LivingEntity attacker) {
        return source(level, REGULARITY_STRIKE, attacker);
    }

    private static DamageSource source(Level level, ResourceKey<DamageType> key, LivingEntity attacker) {
        Holder<DamageType> type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).get(key).orElseThrow();
        return new DamageSource(type, attacker);
    }
}
