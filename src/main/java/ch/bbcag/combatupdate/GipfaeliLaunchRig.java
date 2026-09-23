package ch.bbcag.combatupdate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import ch.bbcag.combatupdate.entity.GipfaeliBomb;

// The Gipfaeli launch rig: the thing a strike is called with, and then set down.
//
// Two presses on the one button, in that order. The first picks the block the bomb lands on, which
// is then marked until it is used. The second sets the bomb down in front of the player, and because
// the spot was settled first, the five seconds start right there and then - see GipfaeliBomb for the
// countdown and the arc.
//
// Aiming before placing rather than after is what makes the spot binding: there is never a bomb
// sitting in the world waiting to be told where to go, and so never a moment where the answer can
// change. Sneaking picks a new block instead of placing, so a spot taken by mistake costs a press
// rather than a pastry.
//
// Aiming is deliberately not the launcher's lock-on sight. That sight takes something that moves,
// and tells a rocket to chase it; this takes a place, and the place stays where it was put.
public final class GipfaeliLaunchRig {
    // The block each player has called and not yet set a bomb on. Server-side, and only ever one per
    // player: calling a second spot replaces the first.
    private static final Map<UUID, Vec3> TARGETS = new ConcurrentHashMap<>();

    // Long enough to outlast the repeat rate of a held right-click, so calling a spot and setting the
    // bomb down cannot both happen inside the one press.
    private static final int CLICK_COOLDOWN_TICKS = 10;

    // How far in front of the player a bomb can be set down. Placing is a thing done at arm's length;
    // it is aiming that reaches across the map.
    private static final double PLACE_REACH = 6.0;

    // Both of these go out as packets, so neither is done every tick.
    private static final int MARKER_INTERVAL_TICKS = 10;
    private static final int READOUT_INTERVAL_TICKS = 20;

    private GipfaeliLaunchRig() {
    }

    // Returns whether the rig took the click, which is the caller's cue to cancel the interaction so
    // nothing else acts on the same press.
    public static boolean use(Player player, ItemStack stack, Level level) {
        if (!Config.on(Config.ENABLE_GIPFAELI_BOMB) || !stack.is(CombatUpdate.GIPFAELI_LAUNCH_RIG.get())) {
            return false;
        }

        if (player.getCooldowns().isOnCooldown(stack)) {
            return false;
        }

        if (level instanceof ServerLevel serverLevel) {
            // With no spot called there is nothing to set a bomb on, so the press can only be an aim.
            // Sneaking is how you say so anyway, and re-aim a spot you would rather not keep.
            if (player.isShiftKeyDown() || !TARGETS.containsKey(player.getUUID())) {
                callSpot(serverLevel, player);
            } else {
                place(serverLevel, player);
            }
        }

        player.getCooldowns().addCooldown(stack, CLICK_COOLDOWN_TICKS);
        return true;
    }

    private static void callSpot(ServerLevel level, Player player) {
        Vec3 spot = pickSpot(level, player);
        TARGETS.put(player.getUUID(), spot);

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.COMPARATOR_CLICK, SoundSource.PLAYERS, 0.8F, 1.6F);
        mark(level, player, spot);
        announce(player, spot);
    }

    private static void place(ServerLevel level, Player player) {
        Vec3 spot = TARGETS.get(player.getUUID());
        if (spot == null) {
            return;
        }

        BlockHitResult ground = pickGround(level, player);
        if (ground == null) {
            refuse(level, player, "combatupdate.gipfaeli.bomb.no_ground");
            return;
        }

        // Creative pays for nothing, and neither does anyone who has turned the ammo off - the same
        // bargain the launcher offers, out of the same tin of pastry.
        boolean free = player.getAbilities().instabuild || !Config.GIPFAELI_CONSUMES_AMMO.get();
        int ammoSlot = free ? GipfaeliLauncher.NO_AMMO : GipfaeliLauncher.findAmmoSlot(player);
        if (!free && ammoSlot == GipfaeliLauncher.NO_AMMO) {
            refuse(level, player, "combatupdate.gipfaeli.empty");
            return;
        }

        // On the face that was clicked rather than inside the block: a bomb sits on the ground the way
        // anything else placed against a block does.
        Vec3 seat = Vec3.atBottomCenterOf(ground.getBlockPos().relative(ground.getDirection()));
        level.addFreshEntity(new GipfaeliBomb(level, seat, spot, player));

        // The spot is spent. The next press aims again rather than dropping a second bomb on a target
        // that has already been sent one.
        TARGETS.remove(player.getUUID());

        if (!free) {
            player.getInventory().getItem(ammoSlot).shrink(1);
        }

        level.playSound(null, seat.x, seat.y, seat.z,
                SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.9F, 1.4F);
        GipfaeliLock.readout(player, Component.translatable("combatupdate.gipfaeli.bomb.armed",
                Mth.floor(spot.x), Mth.floor(spot.y), Mth.floor(spot.z),
                (Config.GIPFAELI_BOMB_COUNTDOWN_TICKS.getAsInt() + 19) / 20));
    }

    // Keeps the called spot lit and named while the rig waits for a bomb to be set on it, so aiming
    // first and placing second has something to look at in between. Driven from the player tick.
    public static void tick(Player player) {
        if (TARGETS.isEmpty() || !(player.level() instanceof ServerLevel level)) {
            return;
        }

        Vec3 spot = TARGETS.get(player.getUUID());
        if (spot == null) {
            return;
        }

        // Putting the rig away drops the spot. The aim belongs to the tool, so holding something else
        // is the plainest way to say you are done with it.
        if (!Config.on(Config.ENABLE_GIPFAELI_BOMB) || !holdingRig(player)) {
            TARGETS.remove(player.getUUID());
            GipfaeliLock.readout(player, Component.translatable("combatupdate.gipfaeli.bomb.dropped"));
            return;
        }

        if (player.tickCount % MARKER_INTERVAL_TICKS == 0) {
            mark(level, player, spot);
        }

        if (player.tickCount % READOUT_INTERVAL_TICKS == 0) {
            announce(player, spot);
        }
    }

    // The block the strike lands on: whatever the player is looking at, out to the rig's range, taken
    // as the top of that block rather than the point on it the ray happened to touch. A strike is
    // called on a block, not on a spot two pixels down its side, and squaring it up here is what lets
    // the bomb be promised to land on that block from anywhere.
    //
    // A ray that runs out without hitting anything is taken down to the ground under where it ended,
    // so a strike can be called by pointing at the skyline rather than only at something solid. That
    // is what makes the thing usable at range: at a hundred blocks out there is rarely a wall under
    // the crosshair, and the ground below it is what was meant anyway.
    private static Vec3 pickSpot(ServerLevel level, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(Config.GIPFAELI_BOMB_RANGE.getAsDouble()));

        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
        if (hit.getType() != HitResult.Type.MISS) {
            return Vec3.atBottomCenterOf(hit.getBlockPos().above());
        }

        return Vec3.atBottomCenterOf(level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(end)));
    }

    // Where a bomb can be set down: the face of whatever block is within reach of the crosshair.
    private static @Nullable BlockHitResult pickGround(ServerLevel level, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(PLACE_REACH));

        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    // Lights the called block for the player who called it, and only for them: the spot is worth
    // knowing and not worth broadcasting, and the countdown gives everyone else their warning soon
    // enough. Forced past the client's particle range limit, since the whole point is that it can be
    // a hundred blocks off.
    private static void mark(ServerLevel level, Player player, Vec3 spot) {
        if (player instanceof ServerPlayer serverPlayer) {
            level.sendParticles(serverPlayer, ParticleTypes.END_ROD, true, true,
                    spot.x, spot.y + 0.3, spot.z, 8, 0.2, 0.25, 0.2, 0.0);
        }
    }

    private static void announce(Player player, Vec3 spot) {
        GipfaeliLock.readout(player, Component.translatable("combatupdate.gipfaeli.bomb.target",
                Mth.floor(spot.x), Mth.floor(spot.y), Mth.floor(spot.z)));
    }

    private static boolean holdingRig(Player player) {
        return player.getMainHandItem().is(CombatUpdate.GIPFAELI_LAUNCH_RIG.get())
                || player.getOffhandItem().is(CombatUpdate.GIPFAELI_LAUNCH_RIG.get());
    }

    private static void refuse(ServerLevel level, Player player, String message) {
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.8F, 1.0F);
        GipfaeliLock.readout(player, Component.translatable(message));
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        TARGETS.remove(event.getEntity().getUUID());
    }
}
