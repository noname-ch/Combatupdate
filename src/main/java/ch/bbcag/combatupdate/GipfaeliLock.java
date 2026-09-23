package ch.bbcag.combatupdate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

// The launcher's sight. Sneak and use the launcher to take whatever is nearest the middle of the view,
// and a Gipfaeli fired afterwards bends towards it (see GipfaeliRocket).
//
// Server-side throughout. The lock decides where a rocket flies, which is the server's to settle, and
// what the player is told about it goes out over the action bar rather than through any state the
// client keeps of its own.
//
// What marks the locked target is vanilla's glow: it outlines them through walls, for everyone, with
// no rendering of our own. It is deliberately refreshed on a short duration rather than removed when
// the lock drops, so letting go of a lock cannot strip a glow that something else put there.
public final class GipfaeliLock {
    private static final Map<UUID, UUID> LOCKS = new ConcurrentHashMap<>();

    // The local player's own lock, mirrored on the client so the sight can draw and zoom the moment
    // the button goes down instead of a tick later, the same way ElytraBoost predicts its own boost.
    // Held as an entity id rather than a UUID because that is what a client can look an entity up by.
    private static final int NO_TARGET = -1;
    private static int clientLock = NO_TARGET;

    // What the sight is resting on but has not taken yet, refreshed every tick while the launcher is
    // out. Drawing this is what makes the sight visible before anything is locked, so there is
    // something to aim with rather than a feature you have to already know about to find.
    private static int clientCandidate = NO_TARGET;

    // Long enough to outlast the gap between refreshes, short enough that a dropped lock stops showing
    // almost at once.
    private static final int GLOW_DURATION_TICKS = 40;
    private static final int GLOW_REFRESH_INTERVAL_TICKS = 10;
    private static final int READOUT_INTERVAL_TICKS = 5;

    private GipfaeliLock() {
    }

    // Takes a lock on whatever is nearest the middle of the view, or lets go of the one already held.
    // Returns whether the sight did anything at all, which is the caller's cue to swallow the click.
    public static boolean sight(Player player) {
        if (!(player.level() instanceof ServerLevel level)) {
            // What the server decides is still the only lock that steers a rocket. This runs the same
            // search over the same entities to reach the same answer, purely so the sight has
            // something to draw straightaway.
            if (player.isLocalPlayer()) {
                LivingEntity predicted = clientLock != NO_TARGET ? null : findTarget(player.level(), player);
                clientLock = predicted == null ? NO_TARGET : predicted.getId();
            }
            return true;
        }

        if (LOCKS.remove(player.getUUID()) != null) {
            readout(player, Component.translatable("combatupdate.gipfaeli.released"));
            return true;
        }

        LivingEntity target = findTarget(level, player);
        if (target == null) {
            readout(player, Component.translatable("combatupdate.gipfaeli.no_target"));
            return true;
        }

        LOCKS.put(player.getUUID(), target.getUUID());
        glow(target);
        readout(player, Component.translatable("combatupdate.gipfaeli.locked", target.getDisplayName()));
        return true;
    }

    // The sight's own readout, put on the action bar rather than into chat: it is a reading that
    // replaces itself every tick, not something anyone wants a scrollback of.
    static void readout(Player player, Component message) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(message, true);
        }
    }

    // Whatever this player has locked, or null if that is nothing, or something that has since died or
    // gone out of the world.
    public static @Nullable LivingEntity target(ServerLevel level, Player player) {
        UUID targetId = LOCKS.get(player.getUUID());
        if (targetId == null) {
            return null;
        }

        return level.getEntity(targetId) instanceof LivingEntity target && target.isAlive() ? target : null;
    }

    // Keeps the lock honest: it lapses when the target dies, wanders out of range, or the launcher goes
    // back in the pack. Driven from the player tick.
    public static void tick(Player player) {
        if (!(player.level() instanceof ServerLevel level)) {
            tickClientMirror(player);
            return;
        }

        if (LOCKS.isEmpty() || !LOCKS.containsKey(player.getUUID())) {
            return;
        }

        // Putting the launcher away drops the lock. The sight belongs to the weapon, so holding
        // something else is the plainest way to say you are done aiming.
        if (!holdingLauncher(player) || !Config.on(Config.ENABLE_GIPFAELI)) {
            release(player, "combatupdate.gipfaeli.released");
            return;
        }

        LivingEntity target = target(level, player);
        if (target == null || target.distanceTo(player) > Config.GIPFAELI_LOCK_RANGE.getAsDouble()) {
            release(player, "combatupdate.gipfaeli.lost");
            return;
        }

        if (player.tickCount % GLOW_REFRESH_INTERVAL_TICKS == 0) {
            glow(target);
        }

        // Both of these go out as packets, so neither is done every tick. The range still counts up and
        // down fast enough to read as live, at a fifth of the traffic.
        if (player.tickCount % READOUT_INTERVAL_TICKS == 0) {
            readout(player, Component.translatable("combatupdate.gipfaeli.tracking",
                    target.getDisplayName(), (int) target.distanceTo(player)));
        }
    }

    // The same conditions the server drops a lock under, applied to the mirror so the reticle and the
    // zoom let go at the same moment the real lock does.
    private static void tickClientMirror(Player player) {
        if (!player.isLocalPlayer()) {
            return;
        }

        boolean aiming = Config.on(Config.ENABLE_GIPFAELI) && holdingLauncher(player);

        // Worked out once a tick rather than once a frame: it sweeps every living thing in range, and
        // the answer cannot change faster than the entities themselves move.
        LivingEntity candidate = aiming && clientLock == NO_TARGET ? findTarget(player.level(), player) : null;
        clientCandidate = candidate == null ? NO_TARGET : candidate.getId();

        if (clientLock == NO_TARGET) {
            return;
        }

        LivingEntity target = clientTarget(player.level());
        if (target == null
                || !aiming
                || target.distanceTo(player) > Config.GIPFAELI_LOCK_RANGE.getAsDouble()) {
            clientLock = NO_TARGET;
        }
    }

    // What the sight is resting on but has not taken. Client-side only.
    public static @Nullable LivingEntity clientCandidate(Level level) {
        if (clientCandidate == NO_TARGET) {
            return null;
        }

        return level.getEntity(clientCandidate) instanceof LivingEntity target && target.isAlive() ? target : null;
    }

    // What the local player's sight is drawing on. Client-side only; the server steers rockets off
    // LOCKS instead.
    public static @Nullable LivingEntity clientTarget(Level level) {
        if (clientLock == NO_TARGET) {
            return null;
        }

        return level.getEntity(clientLock) instanceof LivingEntity target && target.isAlive() ? target : null;
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LOCKS.remove(event.getEntity().getUUID());
    }

    private static void release(Player player, String message) {
        LOCKS.remove(player.getUUID());
        readout(player, Component.translatable(message));
    }

    private static boolean holdingLauncher(Player player) {
        return player.getMainHandItem().is(CombatUpdate.GIPFAELI_LAUNCHER.get())
                || player.getOffhandItem().is(CombatUpdate.GIPFAELI_LAUNCHER.get());
    }

    // The living thing nearest the middle of the view, inside the sight's cone and its range. Scored on
    // angle off the centre rather than on distance, so a cow in the foreground doesn't steal a lock
    // meant for the player standing behind it.
    private static @Nullable LivingEntity findTarget(Level level, Player player) {
        double range = Config.GIPFAELI_LOCK_RANGE.getAsDouble();
        boolean needsLineOfSight = Config.GIPFAELI_LOCK_NEEDS_LINE_OF_SIGHT.get();

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();

        // Starting the search at the edge of the cone means only what lies inside it is ever considered,
        // and the best score then walks in towards the centre from there.
        double bestAlignment = Math.cos(Math.toRadians(Config.GIPFAELI_LOCK_CONE_DEGREES.getAsDouble()));
        LivingEntity best = null;

        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(range),
                candidate -> candidate != player && candidate.isAlive() && candidate.isPickable());

        for (LivingEntity candidate : candidates) {
            Vec3 toCandidate = candidate.getBoundingBox().getCenter().subtract(eye);
            double distance = toCandidate.length();
            if (distance > range || distance < 1.0E-4) {
                continue;
            }

            double alignment = toCandidate.scale(1.0 / distance).dot(look);
            if (alignment <= bestAlignment) {
                continue;
            }

            // Checked last: it is the dearest of these tests, and by here only a candidate that would
            // actually win the lock is still in the running.
            if (needsLineOfSight && !player.hasLineOfSight(candidate)) {
                continue;
            }

            bestAlignment = alignment;
            best = candidate;
        }

        return best;
    }

    private static void glow(LivingEntity target) {
        target.addEffect(new MobEffectInstance(
                MobEffects.GLOWING, GLOW_DURATION_TICKS, 0, false, false, false));
    }
}
