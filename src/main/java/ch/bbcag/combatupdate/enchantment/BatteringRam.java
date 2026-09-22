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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
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

    // What each level above the first buys. Deliberately none of it is damage: a heavier helmet does
    // not make you hit harder, it makes you hit through. So the levels go on force instead - a wider
    // gust, a deeper hole, and more of your own speed carried out the far side of the impact.
    private static final float KNOCKBACK_PER_LEVEL = 0.5F;
    private static final float BURST_RADIUS_PER_LEVEL = 1.0F;
    private static final float EXPLOSION_POWER_PER_LEVEL = 0.75F;

    // Percentage points knocked off the speed an impact costs, per level above the first.
    private static final int SPEED_KEPT_PER_LEVEL = 20;

    // How hard the daze pins the player down. Slowness VI leaves them shuffling rather than frozen,
    // which still reads as knocked out without taking the controls away outright.
    private static final int DAZE_SLOWNESS_AMPLIFIER = 5;

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
        float damage = (float) (speed * Config.RAM_DAMAGE_PER_SPEED.getAsDouble());
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

        // The ram costs the player speed, so landing one is a committed attack rather than something
        // that happens in passing - but the better the helmet, the more of the charge survives the
        // hit, until at the top level you barely notice going through something. syncVelocity is what
        // pushes the new velocity out to the client, which would otherwise keep flying on at the
        // speed it still thinks it has.
        int speedLoss = Math.max(0,
                Config.RAM_SPEED_LOSS.getAsInt() - (enchantmentLevel - 1) * SPEED_KEPT_PER_LEVEL);
        player.setDeltaMovement(velocity.scale(1.0 - speedLoss / 100.0));
        player.syncVelocity = true;
        daze(player, speedLoss / 100.0);

        finishImpact(serverLevel, player, enchantmentLevel);
    }

    // Flying into a wall, called from BatteringRamWallMixin once vanilla's own damage has been
    // cancelled. speedLost is how much the collision took off the player, which is the same figure
    // vanilla scored its damage from.
    public static void onWallImpact(Player player, double speedLost, double speedBefore) {
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

        // A wall does not care how good the helmet is - it takes whatever speed it takes - so unlike
        // ramming a mob, the daze here is measured off the collision itself rather than reduced by
        // level. Hitting one flat out still knocks you out cold.
        daze(player, speedBefore > 0.0 ? Math.min(1.0, speedLost / speedBefore) : 1.0);

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

    // What every impact has in common: the blast, the burst, the dent in the helmet, and the wait
    // before the next one.
    private static void finishImpact(ServerLevel level, Player player, int enchantmentLevel) {
        // A block out in front of the eyes, so both go off in whatever was just hit rather than at
        // the player's own feet.
        Vec3 at = player.getEyePosition().add(player.getLookAngle());

        windBurst(level, player, enchantmentLevel, at);
        blast(level, player, at, enchantmentLevel);

        player.getItemBySlot(EquipmentSlot.HEAD)
                .hurtAndBreak(Config.RAM_HELMET_DAMAGE.getAsInt(), player, EquipmentSlot.HEAD);

        COOLDOWNS.put(player.getUUID(), Config.RAM_COOLDOWN_TICKS.getAsInt());
    }

    // The same explosion a wind charge makes: no damage of its own and no terrain broken, just the
    // shove and the gust. TRIGGER still lets it flip a lever or set off TNT, as a wind charge would.
    private static void windBurst(ServerLevel level, Player player, int enchantmentLevel, Vec3 at) {
        // Tested against the configured base rather than the grown radius, so setting it to 0 still
        // means off however high the enchantment goes.
        double configured = Config.RAM_WIND_BURST_RADIUS.getAsDouble();
        if (configured <= 0.0) {
            return;
        }

        float radius = (float) configured + (enchantmentLevel - 1) * BURST_RADIUS_PER_LEVEL;

        float knockback = (float) Config.RAM_WIND_BURST_KNOCKBACK.getAsDouble()
                + (enchantmentLevel - 1) * KNOCKBACK_PER_LEVEL;
        SimpleExplosionDamageCalculator calculator = new SimpleExplosionDamageCalculator(
                true, false, Optional.of(knockback),
                BuiltInRegistries.BLOCK.get(BlockTags.BLOCKS_WIND_CHARGE_EXPLOSIONS).map(Function.identity()));

        level.explode(player, null, calculator, at.x, at.y, at.z, radius, false,
                Level.ExplosionInteraction.TRIGGER,
                ParticleTypes.GUST_EMITTER_SMALL, ParticleTypes.GUST_EMITTER_LARGE,
                WeightedList.of(), SoundEvents.WIND_CHARGE_BURST);
    }

    // A real blast on top of the gust, small enough to read as the impact rather than as ordnance.
    // The rammer is the one exception to it: taking the hit from their own charge would undo the
    // whole point of the helmet having just eaten the wall for them.
    private static void blast(ServerLevel level, Player player, Vec3 at, int enchantmentLevel) {
        double configured = Config.RAM_EXPLOSION_POWER.getAsDouble();
        if (configured <= 0.0) {
            return;
        }

        float power = (float) configured + (enchantmentLevel - 1) * EXPLOSION_POWER_PER_LEVEL;

        level.explode(player, null, new SparesRammer(player), at, power, false,
                Config.RAM_EXPLOSION_BREAKS_BLOCKS.get()
                        ? Level.ExplosionInteraction.TNT
                        : Level.ExplosionInteraction.NONE);
    }

    // Everything in range takes the blast as normal; the player who set it off is skipped. They are
    // still shoved by it, because knockback is worked out separately from damage - which is the half
    // of an explosion worth keeping here anyway.
    private static final class SparesRammer extends ExplosionDamageCalculator {
        private final Player rammer;

        private SparesRammer(Player rammer) {
            this.rammer = rammer;
        }

        @Override
        public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
            return entity != this.rammer && super.shouldDamageEntity(explosion, entity);
        }
    }

    // Knocked out: an impact that costs the player their charge leaves them blind and barely able to
    // move for a moment, in proportion to how much of it went. A high-level ram that hardly slows you
    // hardly dazes you either, which is the other half of what the levels buy.
    private static void daze(Player player, double speedLostFraction) {
        int ticks = (int) Math.round(Config.RAM_STUN_TICKS.getAsInt() * speedLostFraction);
        if (ticks <= 0) {
            return;
        }

        // Neither ambient, visible nor icon'd: a daze this short is over before an effect icon has
        // finished appearing, and the swirl of particles would outlast the daze itself.
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, ticks, 0, false, false, false));
        player.addEffect(new MobEffectInstance(
                MobEffects.SLOWNESS, ticks, DAZE_SLOWNESS_AMPLIFIER, false, false, false));
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
