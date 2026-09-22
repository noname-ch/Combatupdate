package ch.bbcag.combatupdate.enchantment;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.combatupdate.Config;

// Battering Ram, a helmet enchantment: gliding into something hard enough makes the player the hammer.
// The damage is scored off the speed at the moment of impact rather than off a weapon, so what it
// rewards is the long dive in, and whatever is hit gets thrown on along the flight path.
//
// Server-side only. Damage, knockback and durability are all the server's to decide, and unlike the
// rocket boost in ElytraBoost there is nothing here the client could usefully predict - a mispredicted
// impact would teleport the player back mid-dive, which is worse than the tick of latency.
public final class BatteringRam {
    // Ticks left before a player can ram again. Only holds players who have just rammed, so it empties
    // itself the moment they stop.
    private static final Map<UUID, Integer> COOLDOWNS = new ConcurrentHashMap<>();

    // How far past the player's own hitbox counts as a hit. Deliberately small: at glide speed the
    // player crosses several blocks in a tick, and a generous box would let the ram land on things
    // well off to the side of where they actually flew.
    private static final double IMPACT_REACH = 0.4;

    private BatteringRam() {
    }

    public static void tick(Player player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        UUID id = player.getUUID();
        Integer cooling = COOLDOWNS.get(id);
        if (cooling != null) {
            if (cooling <= 1) {
                COOLDOWNS.remove(id);
            } else {
                COOLDOWNS.put(id, cooling - 1);
            }
            return;
        }

        if (!player.isFallFlying()) {
            return;
        }

        int enchantmentLevel = ramLevel(player, serverLevel);
        if (enchantmentLevel <= 0) {
            return;
        }

        // Blocks per tick, which is what the impact is scored on: a rocket-boosted dive runs at
        // roughly 1.5, an unpowered glide at well under half that.
        Vec3 velocity = player.getDeltaMovement();
        double speed = velocity.length();
        if (speed < Config.RAM_MIN_SPEED.getAsDouble()) {
            return;
        }

        List<LivingEntity> struck = serverLevel.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(IMPACT_REACH),
                target -> target != player && target.isAlive() && target.isPickable());
        if (struck.isEmpty()) {
            return;
        }

        DamageSource source = player.damageSources().playerAttack(player);
        float damage = (float) (speed * Config.RAM_DAMAGE_PER_SPEED.getAsDouble() * enchantmentLevel);
        Vec3 heading = velocity.scale(1.0 / speed);

        for (LivingEntity target : struck) {
            target.hurtServer(serverLevel, source, damage);
            // knockback() pushes a target away from the direction it is handed, so the heading goes in
            // negated: that throws them on ahead of the player instead of back through them.
            target.knockback(Config.RAM_KNOCKBACK.getAsDouble() * enchantmentLevel,
                    -heading.x, -heading.z, source, damage);
        }

        serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.MACE_SMASH_AIR, SoundSource.PLAYERS, 1.0F, 1.0F);

        // The ram costs the player the speed it just spent, so landing one is a committed attack
        // rather than something that happens in passing. syncVelocity is what pushes the new velocity
        // out to the client, which would otherwise keep flying on at the speed it still thinks it has.
        player.setDeltaMovement(velocity.scale(1.0 - Config.RAM_SPEED_LOSS.getAsInt() / 100.0));
        player.syncVelocity = true;

        player.getItemBySlot(EquipmentSlot.HEAD)
                .hurtAndBreak(Config.RAM_HELMET_DAMAGE.getAsInt(), player, EquipmentSlot.HEAD);

        COOLDOWNS.put(id, Config.RAM_COOLDOWN_TICKS.getAsInt());
    }

    private static int ramLevel(Player player, ServerLevel level) {
        ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        if (helmet.isEmpty()) {
            return 0;
        }

        return level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .get(ModEnchantments.BATTERING_RAM)
                .map(helmet::getEnchantmentLevel)
                .orElse(0);
    }
}
