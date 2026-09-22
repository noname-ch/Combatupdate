package ch.bbcag.combatupdate.client;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.Config;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// The local player's full 3D attitude while gliding, held as a nose direction plus an "up out of the
// cockpit" direction.
//
// Minecraft steers by nudging two numbers, yaw and pitch. That only behaves like the screen near level
// flight: yaw stops meaning "horizontal" as the nose approaches vertical, and once past vertical the
// whole horizontal axis flips, so the controls silently stop matching the view. Rotating an actual
// orientation instead - mouse and W/S around the aircraft's own up and right axes, A/D around its
// nose - keeps input aligned with what is on screen at every attitude, which is how a flight sim
// handles it.
//
// Yaw and pitch are then derived back out of the nose direction for the game (movement and aim still
// run off them), and the leftover rotation about the nose is the roll handed to the camera.
public final class ElytraOrientation {
    // Carries the bank angle into rendering: the render events only receive a snapshot of the entity,
    // not the entity itself, so there's no other way to tell which avatar being drawn is ours.
    public static final ContextKey<Float> RENDER_ROLL =
            new ContextKey<>(Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "elytra_roll"));

    private static final Vec3 WORLD_UP = new Vec3(0.0, 1.0, 0.0);

    // Below this, an eased rate counts as having come to rest, so a released key settles instead of
    // trailing an ever-smaller rotation behind it forever.
    private static final float RESTING_DEGREES_PER_SECOND = 0.05F;

    private static boolean active;
    private static Vec3 forward = new Vec3(0.0, 0.0, 1.0);
    private static Vec3 up = WORLD_UP;
    private static float rollRate;
    private static float pitchRate;
    private static long lastFrameNanos;

    private ElytraOrientation() {
    }

    public static void ensureActive(LivingEntity player) {
        if (active) {
            return;
        }

        forward = player.getLookAngle().normalize();
        up = horizonUp(forward);
        rollRate = 0.0F;
        pitchRate = 0.0F;
        active = true;
        lastFrameNanos = 0L;
    }

    public static void stop() {
        active = false;
    }

    // The keyboard axes: A/D bank about the nose, W/S swing it down and up about the wings. Both are
    // driven per frame off wall-clock time rather than per tick, because a tick-sized step is 1/20th of
    // a second of rotation applied at once, which reads as the view ratcheting round instead of
    // sweeping.
    //
    // Each axis eases its rate towards the rate the key is asking for instead of jumping to it, so a
    // press winds the rotation up and a release lets it run down, rather than the view snapping into
    // motion and then dead-stopping. The mouse deliberately stays direct: smoothing a mouse reads as
    // lag, while smoothing a key reads as weight.
    //
    // Positive pitch is nose-up. Returns whether the nose actually moved, which is the caller's cue
    // that the entity's own yaw and pitch need bringing back in line with it - banking alone leaves
    // the nose where it was, so it needs no such fixup.
    public static boolean advanceKeys(int rollDirection, int pitchDirection) {
        long now = System.nanoTime();
        float seconds = lastFrameNanos == 0L ? 0.0F : (now - lastFrameNanos) / 1.0E9F;
        lastFrameNanos = now;

        if (seconds <= 0.0F) {
            return false;
        }

        // Clamped so a stutter or a paused game can't dump a huge rotation in on the next frame.
        seconds = Math.min(seconds, 0.1F);

        // Framerate-independent ease: over one ramp's worth of time the rate closes the same fraction
        // of the remaining gap no matter how the frames happen to fall.
        float ramp = (float) Config.CONTROL_RAMP_SECONDS.getAsDouble();
        float blend = ramp <= 0.0F ? 1.0F : 1.0F - (float) Math.exp(-seconds / ramp);

        rollRate = ease(rollRate, rollDirection * Config.ROLL_SPEED.getAsInt(), blend);
        pitchRate = ease(pitchRate, pitchDirection * Config.PITCH_SPEED.getAsInt(), blend);

        if (rollRate != 0.0F) {
            up = rotateAbout(up, forward, (float) Math.toRadians(rollRate * seconds));
        }

        boolean noseMoved = pitchRate != 0.0F;
        if (noseMoved) {
            float radians = (float) Math.toRadians(pitchRate * seconds);
            Vec3 right = forward.cross(up).normalize();
            forward = rotateAbout(forward, right, radians);
            up = rotateAbout(up, right, radians);
        }

        if (rollRate != 0.0F || noseMoved) {
            orthonormalize();
        }

        return noseMoved;
    }

    private static float ease(float rate, float target, float blend) {
        float eased = rate + (target - rate) * blend;
        return target == 0.0F && Math.abs(eased) < RESTING_DEGREES_PER_SECOND ? 0.0F : eased;
    }

    public static void applyMouse(double xo, double yo) {
        float yawRadians = (float) Math.toRadians(-xo * 0.15);
        float pitchRadians = (float) Math.toRadians(-yo * 0.15);

        forward = rotateAbout(forward, up, yawRadians);

        Vec3 right = forward.cross(up).normalize();
        forward = rotateAbout(forward, right, pitchRadians);
        up = rotateAbout(up, right, pitchRadians);

        orthonormalize();
    }

    // Applied as deltas to both the current and previous rotation, the way vanilla's turn() does, so
    // the frame-to-frame interpolation of the view stays intact.
    public static void writeRotation(Entity entity) {
        float yaw = (float) -Math.toDegrees(Math.atan2(forward.x, forward.z));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.clamp(forward.y, -1.0, 1.0)));

        float yawDelta = Mth.wrapDegrees(yaw - entity.getYRot());
        float pitchDelta = pitch - entity.getXRot();

        entity.setYRot(entity.getYRot() + yawDelta);
        entity.setXRot(entity.getXRot() + pitchDelta);
        entity.yRotO += yawDelta;
        entity.xRotO += pitchDelta;
    }

    // How far the aircraft is banked: the signed angle from the horizon's "up" to our own, about the nose.
    public static float roll() {
        Vec3 reference = horizonUp(forward);
        double cos = reference.dot(up);
        double sin = reference.cross(up).dot(forward);
        return (float) Math.toDegrees(Math.atan2(sin, cos));
    }

    private static void orthonormalize() {
        forward = forward.normalize();
        up = up.subtract(forward.scale(forward.dot(up)));
        up = up.lengthSqr() < 1.0E-8 ? horizonUp(forward) : up.normalize();
    }

    // World up, with any component along the nose removed. Straight up or down leaves nothing to
    // project, so fall back to an arbitrary perpendicular rather than dividing by zero.
    private static Vec3 horizonUp(Vec3 nose) {
        Vec3 reference = WORLD_UP.subtract(nose.scale(nose.dot(WORLD_UP)));
        if (reference.lengthSqr() < 1.0E-8) {
            reference = new Vec3(1.0, 0.0, 0.0).subtract(nose.scale(nose.x));
        }
        return reference.normalize();
    }

    private static Vec3 rotateAbout(Vec3 vector, Vec3 axis, float radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return vector.scale(cos)
                .add(axis.cross(vector).scale(sin))
                .add(axis.scale(axis.dot(vector) * (1.0 - cos)));
    }
}
