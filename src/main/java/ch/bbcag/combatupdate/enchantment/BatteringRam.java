package ch.bbcag.combatupdate.enchantment;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SimpleExplosionDamageCalculator;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.combatupdate.Config;

// Battering Ram, a helmet enchantment: gliding into something hard enough makes the player the
// hammer. Damage is scored off the speed at the moment of impact rather than off a weapon, so what it
// rewards is the long dive in, and every impact - whether it lands on a mob or on a wall - fires off
// a wind burst.
//
// The helmet also takes the wall so its wearer doesn't; vanilla's fly-into-wall damage is cancelled
// outright in BatteringRamWallMixin, which is what calls onWallImpact in its place.
//
// Server-side only. Damage, knockback, explosions and durability are all the server's to decide, and
// unlike the rocket boost in ElytraBoost there is nothing here the client could usefully predict - a
// mispredicted impact would yank the player back mid-dive, which is worse than the tick of latency.
public final class BatteringRam {
    // Ticks left before a player can ram again, shared by wall and entity impacts so that grinding
    // along a wall doesn't set off a burst every tick. Only holds players who have just rammed.
    private static final Map<UUID, Integer> COOLDOWNS = new ConcurrentHashMap<>();

    // How far past the player's own hitbox counts as a hit. Deliberately small: at glide speed the
    // player crosses several blocks in a tick, and a generous box would let the ram land on things
    // well off to the side of where they actually flew.
    private static final double IMPACT_REACH = 0.4;

    // Vanilla only starts charging fly-into-wall damage once a collision has cost more speed than
    // this - its damage is (speed lost) * 10 - 3, see LivingEntity#handleFallFlyingCollisions. The
    // burst is pitched to fire exactly where that damage used to begin, so the enchantment trades one
    // for the other rather than moving the goalposts.
    private static final double WALL_IMPACT_THRESHOLD = 0.3;

    // What each level above the first adds to the burst's shove, matching vanilla Wind Burst's own
    // progression of roughly 1.2 / 1.7 / 2.2.
    private static final float KNOCKBACK_PER_LEVEL = 0.5F;

    private BatteringRam() {
    }

    // Flying into a mob. Driven from the player tick, which is also where the shared cooldown runs
    // down.
    public static void tick(Player player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (stillCoolingDown(player)) {
            return;
        }

        if (!player.isFallFlying()) {
            return;
        }

        int enchantmentLevel = ramLevel(player);
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

        finishImpact(serverLevel, player, enchantmentLevel);
    }

    // Flying into a wall, called from BatteringRamWallMixin once vanilla's own damage has been
    // cancelled. speedLost is how much the collision took off the player, which is the same figure
    // vanilla scored its damage from.
    public static void onWallImpact(Player player, double speedLost) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (speedLost <= WALL_IMPACT_THRESHOLD || stillCoolingDown(player)) {
            return;
        }

        int enchantmentLevel = ramLevel(player);
        if (enchantmentLevel <= 0) {
            return;
        }

        serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 1.0F, 1.0F);

        finishImpact(serverLevel, player, enchantmentLevel);
    }

    // Read on both sides: the wall mixin runs wherever the collision does, and has to reach the same
    // answer on each so the client doesn't flinch from damage the server never dealt.
    public static int ramLevel(Player player) {
        return ramLevel(player.getItemBySlot(EquipmentSlot.HEAD), player.level());
    }

    // Split out so the client can also ask a bare helmet stack whether it should render flattened,
    // without needing a player to read it off of (see BatteringRamHelmetModel).
    public static int ramLevel(ItemStack helmet, Level level) {
        if (helmet.isEmpty()) {
            return 0;
        }

        return level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .get(ModEnchantments.BATTERING_RAM)
                .map(helmet::getEnchantmentLevel)
                .orElse(0);
    }

    // What every impact has in common: the burst, the dent in the helmet, and the wait before the
    // next one.
    private static void finishImpact(ServerLevel level, Player player, int enchantmentLevel) {
        windBurst(level, player, enchantmentLevel);

        player.getItemBySlot(EquipmentSlot.HEAD)
                .hurtAndBreak(Config.RAM_HELMET_DAMAGE.getAsInt(), player, EquipmentSlot.HEAD);

        COOLDOWNS.put(player.getUUID(), Config.RAM_COOLDOWN_TICKS.getAsInt());
    }

    // The same explosion a wind charge makes, thrown a block out in front of the player so it goes
    // off in whatever they just hit: no damage of its own and no terrain broken, just the shove and
    // the gust. TRIGGER still lets it flip a lever or set off TNT, the way a wind charge would.
    private static void windBurst(ServerLevel level, Player player, int enchantmentLevel) {
        float radius = (float) Config.RAM_WIND_BURST_RADIUS.getAsDouble();
        if (radius <= 0.0F) {
            return;
        }

        float knockback = (float) Config.RAM_WIND_BURST_KNOCKBACK.getAsDouble()
                + (enchantmentLevel - 1) * KNOCKBACK_PER_LEVEL;
        SimpleExplosionDamageCalculator calculator = new SimpleExplosionDamageCalculator(
                true, false, Optional.of(knockback),
                BuiltInRegistries.BLOCK.get(BlockTags.BLOCKS_WIND_CHARGE_EXPLOSIONS).map(Function.identity()));

        Vec3 at = player.getEyePosition().add(player.getLookAngle());
        level.explode(player, null, calculator, at.x, at.y, at.z, radius, false,
                Level.ExplosionInteraction.TRIGGER,
                ParticleTypes.GUST_EMITTER_SMALL, ParticleTypes.GUST_EMITTER_LARGE,
                WeightedList.of(), SoundEvents.WIND_CHARGE_BURST);
    }

    private static boolean stillCoolingDown(Player player) {
        UUID id = player.getUUID();
        Integer remaining = COOLDOWNS.get(id);
        if (remaining == null) {
            return false;
        }

        if (remaining <= 1) {
            COOLDOWNS.remove(id);
        } else {
            COOLDOWNS.put(id, remaining - 1);
        }

        return true;
    }
}
