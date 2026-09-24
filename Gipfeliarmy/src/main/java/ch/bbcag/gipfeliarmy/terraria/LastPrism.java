package ch.bbcag.gipfeliarmy.terraria;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.gipfeliarmy.Config;
import ch.bbcag.gipfeliarmy.GipfaeliArmy;

// The Last Prism, after Terraria's own: hold the use button and it splits light into six beams
// that fan out from the prism and slowly swing together, until after three seconds they merge
// into one great beam that cuts through everything in front of it. Let go and it all stops.
//
// Two of them, as in Terraria: the ordinary rainbow one, and the one Terraria gives a player
// named "Random", whose beams come out in whatever colours they like and change their minds
// every few ticks. They do the same damage; one is simply crafted from the other.
//
// Terraria pays for it in mana. Here it is paid for in hunger (see Config.LAST_PRISM_HUNGER).
//
// The beams are drawn by each client on its own, from where the player holding the prism is
// looking - the server sends nothing for them. It only works out who they hit.
public final class LastPrism extends Item {
    // How long, in ticks, the six beams take to swing together into one.
    static final int CONVERGE_TICKS = 60;
    static final double RANGE = 48.0;
    private static final int BEAMS = 6;
    // How far apart, in radians, the beams start out.
    private static final double MAX_SPREAD = 0.28;
    // Beams deal their damage in pulses this many ticks apart, which is also how long a mob
    // shrugs off further hits after one; any closer and the pulses in between would do nothing.
    private static final int PULSE_TICKS = 10;
    private static final float EXHAUSTION_PER_TICK = 0.06F;

    private final boolean randomColours;

    public LastPrism(Properties properties, boolean randomColours) {
        super(properties);
        this.randomColours = randomColours;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!Config.on(Config.ENABLE_LAST_PRISM)) {
            return InteractionResult.PASS;
        }

        player.startUsingItem(hand);
        if (!level.isClientSide()) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BEACON_ACTIVATE,
                    SoundSource.PLAYERS, 0.8F, 1.6F);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        return 72000;
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.NONE;
    }

    @Override
    public void onUseTick(Level level, LivingEntity user, ItemStack stack, int ticksRemaining) {
        if (!(user instanceof Player player) || !Config.on(Config.ENABLE_LAST_PRISM)) {
            return;
        }

        int used = this.getUseDuration(stack, user) - ticksRemaining;
        List<Beam> beams = beams(player, used);
        if (level.isClientSide()) {
            this.draw(level, beams, used);
            return;
        }

        ServerLevel serverLevel = (ServerLevel) level;
        if (!player.getAbilities().instabuild && Config.LAST_PRISM_HUNGER.get()) {
            player.causeFoodExhaustion(EXHAUSTION_PER_TICK);
        }

        if (used == CONVERGE_TICKS) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BEACON_POWER_SELECT,
                    SoundSource.PLAYERS, 1.0F, 1.4F);
        } else if (used % 20 == 0) {
            float charge = Math.min(1.0F, used / (float) CONVERGE_TICKS);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BEACON_AMBIENT,
                    SoundSource.PLAYERS, 1.0F, 1.0F + charge);
        }

        if (used % PULSE_TICKS == 0) {
            this.hurt(serverLevel, player, beams, used);
        }
    }

    // One beam: where it leaves the prism, where it stops, and which of the six it is.
    record Beam(Vec3 from, Vec3 to, int index, boolean merged) {
    }

    // Where the beams are this tick, for a player who has been holding the prism for this long.
    static List<Beam> beams(Player player, int used) {
        float charge = Math.min(1.0F, used / (float) CONVERGE_TICKS);
        Vec3 look = player.getLookAngle();
        Vec3 up = Vec3.directionFromRotation(player.getXRot() - 90.0F, player.getYRot());
        Vec3 right = look.cross(up);
        Vec3 origin = player.getEyePosition().add(look.scale(0.9)).subtract(up.scale(0.25));

        List<Beam> beams = new ArrayList<>();
        if (charge >= 1.0F) {
            beams.add(new Beam(origin, clip(player, origin, look), 0, true));
            return beams;
        }

        double spread = MAX_SPREAD * (1.0 - charge);
        double spin = used * 0.12;
        for (int i = 0; i < BEAMS; i++) {
            double angle = spin + i * Math.PI * 2.0 / BEAMS;
            Vec3 off = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
            Vec3 direction = look.scale(Math.cos(spread)).add(off.scale(Math.sin(spread))).normalize();
            beams.add(new Beam(origin, clip(player, origin, direction), i, false));
        }
        return beams;
    }

    private static Vec3 clip(Player player, Vec3 from, Vec3 direction) {
        Vec3 to = from.add(direction.scale(RANGE));
        return player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getLocation();
    }

    private void hurt(ServerLevel level, Player player, List<Beam> beams, int used) {
        float charge = Math.min(1.0F, used / (float) CONVERGE_TICKS);
        float perBeam = beams.size() == 1
                ? (float) Config.LAST_PRISM_DAMAGE.getAsDouble()
                : (float) Config.LAST_PRISM_DAMAGE.getAsDouble() * (0.1F + 0.15F * charge);

        Map<LivingEntity, Float> damage = new HashMap<>();
        for (Beam beam : beams) {
            double thickness = beam.merged() ? 0.6 : 0.25;
            AABB sweep = new AABB(beam.from(), beam.to()).inflate(thickness + 1.0);
            for (Entity entity : level.getEntities(player, sweep)) {
                if (!(entity instanceof LivingEntity living) || !living.isAlive() || !living.isPickable()
                        || GipfaeliArmy.sameSide(player, living)) {
                    continue;
                }
                if (living.getBoundingBox().inflate(thickness).clip(beam.from(), beam.to()).isPresent()) {
                    damage.merge(living, perBeam, Float::sum);
                }
            }
        }

        DamageSource source = player.damageSources().indirectMagic(player, player);
        for (Map.Entry<LivingEntity, Float> hit : damage.entrySet()) {
            hit.getKey().hurtServer(level, source, hit.getValue());
        }
    }

    // --- Client: drawing the beams -------------------------------------------------------------

    private void draw(Level level, List<Beam> beams, int used) {
        for (Beam beam : beams) {
            Vec3 path = beam.to().subtract(beam.from());
            double length = path.length();
            if (length < 0.01) {
                continue;
            }
            Vec3 step = path.normalize();

            if (beam.merged()) {
                // The merged beam: a white-hot core wrapped in colour.
                for (double d = 0.0; d < length; d += 0.3) {
                    Vec3 at = beam.from().add(step.scale(d));
                    level.addParticle(new DustParticleOptions(0xFFFFFF, 1.3F), at.x, at.y, at.z, 0, 0, 0);
                    if (level.getRandom().nextBoolean()) {
                        int colour = this.colour(level, (int) (d * 2.0), used, d);
                        double r = 0.35;
                        level.addParticle(new DustParticleOptions(colour, 2.2F),
                                at.x + (level.getRandom().nextDouble() - 0.5) * r,
                                at.y + (level.getRandom().nextDouble() - 0.5) * r,
                                at.z + (level.getRandom().nextDouble() - 0.5) * r, 0, 0, 0);
                    }
                }
                continue;
            }

            // A fanned beam. Every other tick each, so six of them do not drown the screen.
            if ((used + beam.index()) % 2 != 0) {
                continue;
            }
            int colour = this.colour(level, beam.index(), used, 0.0);
            for (double d = 0.0; d < length; d += 0.7) {
                Vec3 at = beam.from().add(step.scale(d + level.getRandom().nextDouble() * 0.3));
                level.addParticle(new DustParticleOptions(colour, 0.9F), at.x, at.y, at.z, 0, 0, 0);
            }
        }
    }

    // Rainbow, turning slowly; or, for the Random prism, anything at all, re-rolled every few ticks.
    private int colour(Level level, int index, int used, double along) {
        if (this.randomColours) {
            long seed = index * 0x9E3779B97F4A7C15L + (used / 4) * 0xC2B2AE3D27D4EB4FL + (long) (along / 4.0) * 0x165667B19E3779F9L;
            seed = (seed ^ (seed >>> 29)) * 0xBF58476D1CE4E5B9L;
            seed ^= seed >>> 32;
            float hue = (seed & 0xFFFF) / 65535.0F;
            float value = 0.6F + ((seed >> 16) & 0xFF) / 255.0F * 0.4F;
            return Mth.hsvToRgb(hue, 1.0F, value);
        }

        float hue = (index / (float) BEAMS + used * 0.01F + (float) along * 0.02F) % 1.0F;
        return Mth.hsvToRgb(hue, 0.85F, 1.0F);
    }
}
