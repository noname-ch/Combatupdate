package ch.bbcag.combatupdate.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import ch.bbcag.combatupdate.Config;
import ch.bbcag.combatupdate.enchantment.ModEnchantments;

// Implements the mod's combat perk enchantments (Regularity, Singularity, Armageddon, Lifesteal,
// Combo: Perun, Gamble, Mirror). Gotta Go Fast is purely data-driven (see its enchantment json)
// and doesn't need any code here.
//
// Damage-instance bookkeeping: a hit only drives these enchants if it's either a genuine player
// weapon swing (vanilla's "is_player_attack" damage type) or Regularity's own delayed extra hit
// (combatupdate:regularity_strike). Regularity's extra hit still procs Lifesteal/Gamble (per design),
// but never counts towards a "combo" style counter (Combo: Perun's 3rd-strike, Armageddon's streak) -
// only genuine primary strikes do. True damage (combatupdate:true_damage) never re-triggers any of
// these attacker-side procs, which is what keeps Perun/Gamble's own true-damage hits from looping.
public class CombatEnchantmentHandler {
    private static final int REGULARITY_DELAY_TICKS = 1;
    private static final float[] LIFESTEAL_PERCENT_BY_LEVEL = {0.04F, 0.08F, 0.13F};
    private static final float[] GAMBLE_HEARTS_BY_LEVEL = {1.0F, 2.0F, 3.0F};

    private static final Map<UUID, Integer> PERUN_COMBOS = new HashMap<>();
    private static final Map<UUID, Integer> ARMAGEDDON_STREAKS = new HashMap<>();
    private static final List<PendingRegularityHit> PENDING_REGULARITY_HITS = new ArrayList<>();

    private record PendingRegularityHit(ServerLevel level, UUID attackerId, UUID targetId, float damage, long dueTick) {
    }

    @SubscribeEvent
    public static void onLivingDamagePre(LivingDamageEvent.Pre event) {
        if (!Config.on(Config.ENABLE_ENCHANTMENTS)) {
            return;
        }

        DamageSource source = event.getSource();
        LivingEntity target = event.getEntity();

        if (source.is(CombatDamageTypes.TRUE_DAMAGE)) {
            // True damage bypasses Singularity's cap and Armageddon's bonus entirely; only Mirror can stop it.
            if (ModEnchantments.getLevel(ModEnchantments.MIRROR, target) > 0) {
                event.setNewDamage(0);
            }
            return;
        }

        if (source.is(DamageTypeTags.IS_PLAYER_ATTACK) && source.getEntity() instanceof Player attacker) {
            applyArmageddonBonus(event, attacker);
        }

        if (ModEnchantments.getLevel(ModEnchantments.SINGULARITY, target) > 0) {
            float capHealth = (float) (Config.SINGULARITY_DAMAGE_CAP_HEARTS.getAsDouble() * 2.0);
            if (event.getNewDamage() > capHealth) {
                event.setNewDamage(capHealth);
            }
        }
    }

    @SubscribeEvent
    public static void onLivingDamagePost(LivingDamageEvent.Post event) {
        if (!Config.on(Config.ENABLE_ENCHANTMENTS)) {
            return;
        }

        float healthDamage = event.getHealthDamage();
        if (healthDamage <= 0) {
            return;
        }

        LivingEntity target = event.getEntity();
        if (!(target.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof Player attacker)) {
            return;
        }

        boolean primaryStrike = source.is(DamageTypeTags.IS_PLAYER_ATTACK);
        boolean regularityStrike = source.is(CombatDamageTypes.REGULARITY_STRIKE);
        if (!primaryStrike && !regularityStrike) {
            return;
        }

        if (primaryStrike) {
            maybeScheduleRegularity(serverLevel, attacker, target, healthDamage);
            maybeTriggerPerunCombo(serverLevel, attacker, target);
            incrementArmageddonStreak(target, attacker);
        }

        applyLifesteal(attacker, healthDamage);
        maybeTriggerGamble(serverLevel, attacker, target);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING_REGULARITY_HITS.isEmpty()) {
            return;
        }

        // Dropped rather than left queued: switching the enchantments off should not land a second hit
        // a tick later from a swing that happened while they were still on.
        if (!Config.on(Config.ENABLE_ENCHANTMENTS)) {
            PENDING_REGULARITY_HITS.clear();
            return;
        }

        long tick = event.getServer().getTickCount();
        Iterator<PendingRegularityHit> iterator = PENDING_REGULARITY_HITS.iterator();
        while (iterator.hasNext()) {
            PendingRegularityHit hit = iterator.next();
            if (tick < hit.dueTick()) {
                continue;
            }
            iterator.remove();

            Entity attackerEntity = hit.level().getEntity(hit.attackerId());
            Entity targetEntity = hit.level().getEntity(hit.targetId());
            if (!(attackerEntity instanceof LivingEntity attacker) || attacker.isRemoved()) {
                continue;
            }
            if (!(targetEntity instanceof LivingEntity target) || !target.isAlive()) {
                continue;
            }

            target.hurtServer(hit.level(), CombatDamageTypes.regularityStrike(hit.level(), attacker), hit.damage());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        PERUN_COMBOS.remove(id);
        ARMAGEDDON_STREAKS.remove(id);
    }

    // --- Singularity's damage cap and Mirror's block are handled directly in onLivingDamagePre. ---

    private static void applyArmageddonBonus(LivingDamageEvent.Pre event, Player attacker) {
        if (ModEnchantments.getLevel(ModEnchantments.ARMAGEDDON, attacker) <= 0) {
            return;
        }

        int required = Config.ARMAGEDDON_STREAK_REQUIRED.getAsInt();
        int streak = ARMAGEDDON_STREAKS.getOrDefault(attacker.getUUID(), 0);
        if (streak >= required) {
            float bonus = (float) (1.0 + Config.ARMAGEDDON_DAMAGE_BONUS_PERCENT.getAsDouble());
            event.setNewDamage(event.getNewDamage() * bonus);
        }
        // Landing a hit always breaks the drought, whether or not the bonus fired.
        ARMAGEDDON_STREAKS.put(attacker.getUUID(), 0);
    }

    private static void incrementArmageddonStreak(LivingEntity target, Player attacker) {
        if (target == attacker || !(target instanceof Player)) {
            return;
        }
        if (ModEnchantments.getLevel(ModEnchantments.ARMAGEDDON, target) <= 0) {
            return;
        }

        int required = Config.ARMAGEDDON_STREAK_REQUIRED.getAsInt();
        int streak = ARMAGEDDON_STREAKS.getOrDefault(target.getUUID(), 0);
        ARMAGEDDON_STREAKS.put(target.getUUID(), Math.min(streak + 1, required));
    }

    private static void maybeScheduleRegularity(ServerLevel serverLevel, Player attacker, LivingEntity target, float healthDamage) {
        if (ModEnchantments.getLevel(ModEnchantments.REGULARITY, attacker) <= 0) {
            return;
        }

        float thresholdHealth = (float) (Config.REGULARITY_THRESHOLD_HEARTS.getAsDouble() * 2.0);
        if (healthDamage >= thresholdHealth) {
            return;
        }

        float secondHitDamage = (float) (healthDamage * Config.REGULARITY_SECOND_HIT_PERCENT.getAsDouble());
        if (secondHitDamage <= 0) {
            return;
        }

        long dueTick = serverLevel.getServer().getTickCount() + REGULARITY_DELAY_TICKS;
        PENDING_REGULARITY_HITS.add(new PendingRegularityHit(serverLevel, attacker.getUUID(), target.getUUID(), secondHitDamage, dueTick));
    }

    private static void maybeTriggerPerunCombo(ServerLevel serverLevel, Player attacker, LivingEntity target) {
        int level = ModEnchantments.getLevel(ModEnchantments.COMBO_PERUN, attacker);
        if (level <= 0) {
            return;
        }

        int combo = PERUN_COMBOS.merge(attacker.getUUID(), 1, Integer::sum);
        if (combo % 3 != 0) {
            return;
        }

        int baseHearts = level == 1 ? 1 : 2;
        boolean targetHasSingularity = ModEnchantments.getLevel(ModEnchantments.SINGULARITY, target) > 0;
        int extraHearts = (level == 3 && targetHasSingularity) ? 1 : 0;
        float trueDamage = (baseHearts + extraHearts) * 2.0F;

        target.hurtServer(serverLevel, CombatDamageTypes.trueDamage(serverLevel, attacker), trueDamage);
        strikeLightning(serverLevel, attacker, target);
    }

    private static void strikeLightning(ServerLevel serverLevel, Player attacker, LivingEntity target) {
        LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(serverLevel, EntitySpawnReason.TRIGGERED);
        if (bolt == null) {
            return;
        }

        bolt.setPos(target.getX(), target.getY(), target.getZ());
        bolt.setVisualOnly(true);
        if (attacker instanceof ServerPlayer serverPlayer) {
            bolt.setCause(serverPlayer);
        }
        serverLevel.addFreshEntity(bolt);
    }

    private static void applyLifesteal(Player attacker, float healthDamage) {
        int level = ModEnchantments.getLevel(ModEnchantments.LIFESTEAL, attacker);
        if (level <= 0) {
            return;
        }

        // Clamped: a level past the table's max still comes in through /give or another mod.
        float percent = LIFESTEAL_PERCENT_BY_LEVEL[Math.min(level, LIFESTEAL_PERCENT_BY_LEVEL.length) - 1];
        float maxHeal = (float) (Config.LIFESTEAL_MAX_HEAL_HEARTS.getAsDouble() * 2.0);
        float heal = Math.min(healthDamage * percent, maxHeal);
        if (heal > 0) {
            attacker.heal(heal);
        }
    }

    private static void maybeTriggerGamble(ServerLevel serverLevel, Player attacker, LivingEntity target) {
        int level = ModEnchantments.getLevel(ModEnchantments.GAMBLE, attacker);
        if (level <= 0) {
            return;
        }

        RandomSource random = attacker.getRandom();
        if (random.nextFloat() >= Config.GAMBLE_CHANCE.getAsDouble()) {
            return;
        }

        LivingEntity victim = random.nextBoolean() ? attacker : target;
        float damage = GAMBLE_HEARTS_BY_LEVEL[Math.min(level, GAMBLE_HEARTS_BY_LEVEL.length) - 1] * 2.0F;
        victim.hurtServer(serverLevel, CombatDamageTypes.trueDamage(serverLevel, attacker), damage);
    }
}
