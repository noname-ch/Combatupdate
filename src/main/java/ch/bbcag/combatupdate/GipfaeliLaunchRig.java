package ch.bbcag.combatupdate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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

import net.minecraft.core.BlockPos;

import ch.bbcag.combatupdate.entity.GipfaeliBomb;

// The Gipfaeli launch rig: the thing a Gipfaeli bomb is set down with, and then aimed by.
//
// Two presses on the one button, told apart by whether the player already has a bomb waiting on a
// spot. The first press sets a bomb on the ground in front of them; the second calls the spot it
// flies to, wherever they are looking. From there the bomb is on its own - see GipfaeliBomb for the
// countdown and the arc.
//
// Aiming is deliberately not the launcher's lock-on sight. That sight takes something that moves, and
// tells a rocket to chase it; this takes a place, which then stays where it was put for the five
// seconds it takes the bomb to leave. A strike called on a spot is a different promise from a shot
// fired at a target, and neither one wants the other's controls.
public final class GipfaeliLaunchRig {
    // Which bomb each player has set down and not yet called a spot on. Server-side, and only ever
    // holding the one per player: a second bomb can only be set down once the first has been sent.
    private static final Map<UUID, UUID> WAITING = new ConcurrentHashMap<>();

    // Long enough to outlast the repeat rate of a held right-click, so setting a bomb down and aiming
    // it cannot both happen inside the one press.
    private static final int CLICK_COOLDOWN_TICKS = 10;

    // How far in front of the player a bomb can be set down. Placing is a thing done at arm's length;
    // it is aiming that reaches across the map.
    private static final double PLACE_REACH = 6.0;

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
            GipfaeliBomb waiting = waitingBomb(serverLevel, player);
            if (waiting == null) {
                place(serverLevel, player);
            } else {
                callStrike(serverLevel, player, waiting);
            }
        }

        player.getCooldowns().addCooldown(stack, CLICK_COOLDOWN_TICKS);
        return true;
    }

    private static void place(ServerLevel level, Player player) {
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
        GipfaeliBomb bomb = new GipfaeliBomb(level, seat, player, !free);
        level.addFreshEntity(bomb);
        WAITING.put(player.getUUID(), bomb.getUUID());

        if (!free) {
            player.getInventory().getItem(ammoSlot).shrink(1);
        }

        level.playSound(null, seat.x, seat.y, seat.z,
                SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.9F, 1.4F);
        GipfaeliLock.readout(player, Component.translatable("combatupdate.gipfaeli.bomb.placed"));
    }

    private static void callStrike(ServerLevel level, Player player, GipfaeliBomb bomb) {
        Vec3 spot = pickSpot(level, player);
        int countdown = bomb.arm(spot);

        // The rig is free for the next bomb the moment this one has somewhere to be.
        WAITING.remove(player.getUUID());

        GipfaeliLock.readout(player, Component.translatable("combatupdate.gipfaeli.bomb.armed",
                Mth.floor(spot.x), Mth.floor(spot.y), Mth.floor(spot.z), (countdown + 19) / 20));
    }

    // The spot the strike is called on: wherever the player is looking, out to the rig's range.
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
            return hit.getLocation();
        }

        BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(end));
        return Vec3.atBottomCenterOf(ground);
    }

    // Where a bomb can be set down: the face of whatever block is within reach of the crosshair.
    private static @Nullable BlockHitResult pickGround(ServerLevel level, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(PLACE_REACH));

        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    // The bomb this player is still to aim, or null if there isn't one. A bomb that went off, was
    // blown up, or went out of the world with the chunk it stood on leaves the rig free to set down
    // another rather than jamming on a reference to something that is no longer there.
    private static @Nullable GipfaeliBomb waitingBomb(ServerLevel level, Player player) {
        UUID bombId = WAITING.get(player.getUUID());
        if (bombId == null) {
            return null;
        }

        if (!(level.getEntity(bombId) instanceof GipfaeliBomb bomb) || !bomb.isAlive() || !bomb.isWaiting()) {
            WAITING.remove(player.getUUID());
            return null;
        }

        return bomb;
    }

    private static void refuse(ServerLevel level, Player player, String message) {
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.8F, 1.0F);
        GipfaeliLock.readout(player, Component.translatable(message));
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        WAITING.remove(event.getEntity().getUUID());
    }
}
