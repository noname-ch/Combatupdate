package ch.bbcag.gipfeliarmy;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

// What a gun does to the person holding it.
//
// Two separate things, because they are two separate things on a real weapon. The shove is the gun
// pushing back against the shooter's whole body - that is what throws you off a ledge, and off the
// launcher it is enough to ride. The climb is the muzzle rising, which moves where the next round
// goes rather than where you are standing, and then settles most of the way back down as the shooter
// pulls the weapon in again.
//
// The climb is applied to the player's real aim rather than drawn as a camera offset. A view that
// kicks while the crosshair stays honest is a screen effect; recoil that is worth having has to cost
// you the shot you were lining up. That means it belongs to the client - the client owns where a
// player is looking and tells the server afterwards - so this runs there and the server is simply
// told the new angle the same way it is told about any other mouse movement.
//
// The shove goes on both sides for the same reason ElytraBoost does it on both: a player's movement
// is the client's to simulate, and the server applying it alone would be overwritten by the next
// position packet.
public final class GipfaeliRecoil {
    // How hard the gun shoves its holder, how far the muzzle climbs per round, and how far it wanders
    // off to one side while doing it.
    public record Recoil(double kick, double climbDegrees, double swayDegrees) {
        public static final Recoil NONE = new Recoil(0.0, 0.0, 0.0);
    }

    // The launcher's own, kept here rather than in GipfaeliWeapon because the launcher had its
    // trigger long before the army had guns. It is the heaviest thing in the mod to fire: aim at your
    // own feet and it will carry you.
    public static final Recoil LAUNCHER = new Recoil(0.62, 8.0, 0.8);

    // How much of each tick's remaining climb is taken. Spread over a few ticks rather than snapped,
    // so a shot kicks rather than teleports the crosshair.
    private static final double CLIMB_RATE = 0.55;

    // And how much of the climb the shooter pulls back down once the muzzle has finished rising.
    private static final double SETTLE_FRACTION = 0.7;
    private static final double SETTLE_RATE = 0.2;

    // Below this a fraction of a degree is not worth another tick of arithmetic.
    private static final double EPSILON = 0.01;

    // Past this the aim has been walked so far up that further rounds are going over everything
    // anyway, and letting it run only makes the weapon unusable rather than hard.
    private static final double MAX_CLIMB_DEGREES = 35.0;

    // The local player's own recoil. One set of figures rather than a map, because only the player at
    // this keyboard has an aim for us to move.
    private static double pitchLeft;
    private static double yawLeft;
    private static double owed;

    private GipfaeliRecoil() {
    }

    // One round's worth. Called from both sides of the trigger: the shove is applied wherever it
    // lands, and the climb is queued only for the player whose aim it is.
    public static void apply(Player player, Recoil recoil) {
        double kick = recoil.kick() * Config.WEAPON_KNOCKBACK.getAsDouble();
        if (kick > 0.0) {
            // Straight back along the barrel, so a shot at your own feet lifts you and a shot at the
            // horizon only staggers you.
            player.setDeltaMovement(player.getDeltaMovement().add(player.getLookAngle().scale(-kick)));
        }

        if (!player.isLocalPlayer()) {
            return;
        }

        double climb = recoil.climbDegrees() * Config.WEAPON_RECOIL.getAsDouble();
        if (climb <= 0.0) {
            return;
        }

        // Negative pitch is up. Sustained fire stacks, which is what walks a machine gun's aim into
        // the sky and why anyone fires one in bursts.
        pitchLeft = Math.max(pitchLeft - climb, -MAX_CLIMB_DEGREES);
        yawLeft += (player.getRandom().nextDouble() - 0.5) * 2.0
                * recoil.swayDegrees() * Config.WEAPON_RECOIL.getAsDouble();
        owed += climb * SETTLE_FRACTION;
    }

    // Eases whatever is owed onto the aim, a share of it per tick. Driven from the player tick.
    public static void tick(Player player) {
        if (!player.isLocalPlayer()) {
            return;
        }

        if (pitchLeft != 0.0 || yawLeft != 0.0) {
            double pitchStep = pitchLeft * CLIMB_RATE;
            double yawStep = yawLeft * CLIMB_RATE;
            player.setXRot(player.getXRot() + (float) pitchStep);
            player.setYRot(player.getYRot() + (float) yawStep);

            pitchLeft = Math.abs(pitchLeft -= pitchStep) < EPSILON ? 0.0 : pitchLeft;
            yawLeft = Math.abs(yawLeft -= yawStep) < EPSILON ? 0.0 : yawLeft;
            return;
        }

        // Only once the muzzle has stopped rising, so the settle never fights the kick.
        if (owed != 0.0) {
            double step = owed * SETTLE_RATE;
            player.setXRot(player.getXRot() + (float) step);
            owed = Math.abs(owed -= step) < EPSILON ? 0.0 : owed;
        }
    }
}
