package ch.bbcag.combatupdate.client;

import ch.bbcag.combatupdate.Config;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// The local player's full 3D attitude while gliding, held as a nose direction plus an "up out of the
// cockpit" direction.
//
// Minecraft steers by nudging two numbers, yaw and pitch. That only behaves like the screen near level
// flight: yaw stops meaning "horizontal" as the nose approaches vertical, and once past vertical the
// whole horizontal axis flips, so the controls silently stop matching the view. Rotating an actual
// orientation instead - mouse around the aircraft's own up and right axes, A/D around its nose - keeps
// input aligned with what is on screen at every attitude, which is how a flight sim handles it.
//
// Yaw and pitch are then derived back out of the nose direction for the game (movement and aim still
// run off them), and the leftover rotation about the nose is the roll handed to the camera.
public final class ElytraOrientation {
    private static final Vec3 WORLD_UP = new Vec3(0.0, 1.0, 0.0);

    private static boolean active;
    private static Vec3 forward = new Vec3(0.0, 0.0, 1.0);
    private static Vec3 up = WORLD_UP;
    private static long lastFrameNanos;

    private ElytraOrientation() {
    }

    public static void ensureActive(LivingEntity player) {
        if (active) {
            return;
        }

        forward = player.getLookAngle().normalize();
        up = horizonUp(forward);
        active = true;
        lastFrameNanos = 0L;
    }

    public static void stop() {
        active = false;
    }

    // Driven per frame off wall-clock time rather than per tick: a tick-sized step is 1/20th of a
    // second of bank applied at once, which reads as the view ratcheting round instead of sweeping.
    public static void advanceRoll(int direction) {
        long now = System.nanoTime();
        float seconds = lastFrameNanos == 0L ? 0.0F : (now - lastFrameNanos) / 1.0E9F;
        lastFrameNanos = now;

        if (direction == 0 || seconds <= 0.0F) {
            return;
        }

        // Clamped so a stutter or a paused game can't dump a huge rotation in on the next frame.
        float degrees = direction * Config.ROLL_SPEED.getAsInt() * Math.min(seconds, 0.1F);
        up = rotateAbout(up, forward, (float) Math.toRadians(degrees));
        orthonormalize();
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
