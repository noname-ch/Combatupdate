package ch.bbcag.gipfeliarmy.client;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.gipfeliarmy.Config;
import ch.bbcag.gipfeliarmy.GipfaeliLock;

// The launcher's sight, drawn over whatever the local player has locked on to, with the view pulling
// in behind it the way a scope does.
//
// Purely client-side decoration over a lock the server owns. It reads GipfaeliLock's client mirror,
// so it has something to draw the instant the button goes down rather than a round trip later.
//
// The reticle is placed by projecting the target into screen space by hand instead of being drawn out
// in the world. A HUD element is what a sight actually is - it belongs to the screen, at a constant
// size and never behind anything - and the projection is a handful of dot products, against a good
// deal more machinery to billboard a quad in the level and keep it out of the depth buffer.
public final class GipfaeliSight {
    // Locked is hard and red; a target merely under the sight is pale and thin, so the two never read
    // as the same thing at a glance.
    private static final int LOCKED_COLOUR = 0xFFFF4A32;
    private static final int CANDIDATE_COLOUR = 0xB2FFFFFF;
    private static final int RETICLE_SHADOW = 0x66000000;

    // The brackets stay legible on a distant target and stop swallowing the screen on a close one.
    private static final float MIN_HALF_SIZE = 7.0F;
    private static final float MAX_HALF_SIZE = 64.0F;

    private static final int BRACKET_THICKNESS = 2;

    // How much of each bracket's side is actually drawn, so the corners read as a frame rather than
    // as a closed box.
    private static final float BRACKET_ARM_FRACTION = 0.34F;

    // Anything nearer than this along the view axis is beside or behind the camera, where a
    // perspective divide stops meaning anything.
    private static final double MIN_DEPTH = 0.05;

    // The field of view the world is actually being drawn at this frame, captured on its way past so
    // the projection below matches it - zoom included.
    private static float renderFov = 70.0F;

    // 0 is no zoom, 1 is fully wound in. Eased per frame so the view slides rather than snaps.
    private static float zoom;
    private static long lastFrameNanos;

    private GipfaeliSight() {
    }

    // Winds the field of view in while something is sighted, and notes what the world ends up being
    // drawn at either way.
    public static float computeFov(float fov) {
        // Only a taken lock zooms. Pulling the view in every time something wandered under the sight
        // would be unusable.
        boolean sighted = Config.on(Config.ENABLE_GIPFAELI) && locked() != null;
        zoom = ease(zoom, sighted ? 1.0F : 0.0F);

        if (zoom > 0.0F) {
            // Interpolating the divisor rather than the angle keeps the pull-in even: halfway through
            // the ease the view really is half the way to the full magnification.
            float magnification = 1.0F + (float) (Config.GIPFAELI_ZOOM.getAsDouble() - 1.0) * zoom;
            fov /= magnification;
        }

        renderFov = fov;
        return fov;
    }

    public static void render(GuiGraphicsExtractor graphics) {
        if (!Config.on(Config.ENABLE_GIPFAELI) || !Config.GIPFAELI_SHOW_SIGHT.get()) {
            return;
        }

        LivingEntity target = locked();
        boolean isLocked = target != null;
        if (target == null) {
            target = candidate();
        }
        if (target == null) {
            return;
        }

        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        Vec3 forward = Vec3.directionFromRotation(camera.xRot(), camera.yRot());
        // The camera's own up, taken as the look direction tipped a further 90 degrees back. Roll is
        // ignored, which is right for every camera but a banking glide.
        Vec3 up = Vec3.directionFromRotation(camera.xRot() - 90.0F, camera.yRot());
        Vec3 right = forward.cross(up);

        Vec3 relative = target.getBoundingBox().getCenter().subtract(camera.position());
        double depth = relative.dot(forward);
        if (depth < MIN_DEPTH) {
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();

        // Standard perspective divide. Working in GUI units throughout means the same scale serves
        // both axes and no aspect ratio has to come into it.
        double scale = (height / 2.0) / Math.tan(Math.toRadians(renderFov) / 2.0);
        double centreX = width / 2.0 + relative.dot(right) / depth * scale;
        double centreY = height / 2.0 - relative.dot(up) / depth * scale;

        float halfSize = (float) Math.clamp(
                target.getBbHeight() * 0.62 / depth * scale, MIN_HALF_SIZE, MAX_HALF_SIZE);

        drawReticle(graphics, (int) Math.round(centreX), (int) Math.round(centreY), halfSize, isLocked);
    }

    private static void drawReticle(GuiGraphicsExtractor graphics, int centreX, int centreY,
            float halfSize, boolean isLocked) {
        int extent = Math.round(halfSize);
        int arm = Math.max(3, Math.round(halfSize * 2 * BRACKET_ARM_FRACTION));
        int thickness = isLocked ? BRACKET_THICKNESS : 1;

        int left = centreX - extent;
        int right = centreX + extent;
        int top = centreY - extent;
        int bottom = centreY + extent;

        // Laid down twice, the shadow pass offset by a pixel, so the brackets stay readable against a
        // bright sky as well as against dark ground.
        for (int pass = 0; pass < 2; pass++) {
            boolean shadowPass = pass == 0;
            int colour = shadowPass ? RETICLE_SHADOW : (isLocked ? LOCKED_COLOUR : CANDIDATE_COLOUR);
            int nudge = shadowPass ? 1 : 0;

            corner(graphics, left + nudge, top + nudge, arm, thickness, colour, true, true);
            corner(graphics, right + nudge, top + nudge, arm, thickness, colour, false, true);
            corner(graphics, left + nudge, bottom + nudge, arm, thickness, colour, true, false);
            corner(graphics, right + nudge, bottom + nudge, arm, thickness, colour, false, false);

            // Only a real lock gets the centre pip, so the two states differ by more than colour.
            if (isLocked) {
                graphics.fill(centreX - 1 + nudge, centreY - 1 + nudge,
                        centreX + 1 + nudge, centreY + 1 + nudge, colour);
            }
        }
    }

    // One L of the frame: an arm along each axis, running inwards from the corner it is drawn at.
    private static void corner(GuiGraphicsExtractor graphics, int x, int y, int arm, int thickness,
            int colour, boolean fromLeft, boolean fromTop) {
        int horizontal = fromLeft ? arm : -arm;
        int vertical = fromTop ? arm : -arm;
        int thickX = fromLeft ? thickness : -thickness;
        int thickY = fromTop ? thickness : -thickness;

        graphics.fill(Math.min(x, x + horizontal), Math.min(y, y + thickY),
                Math.max(x, x + horizontal), Math.max(y, y + thickY), colour);
        graphics.fill(Math.min(x, x + thickX), Math.min(y, y + vertical),
                Math.max(x, x + thickX), Math.max(y, y + vertical), colour);
    }

    private static @Nullable LivingEntity locked() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? null : GipfaeliLock.clientTarget(minecraft.level);
    }

    private static @Nullable LivingEntity candidate() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? null : GipfaeliLock.clientCandidate(minecraft.level);
    }

    // Framerate-independent ease: over one wind-in's worth of time the zoom closes the same fraction
    // of what is left no matter how the frames happen to fall.
    private static float ease(float current, float towards) {
        long now = System.nanoTime();
        float seconds = lastFrameNanos == 0L ? 0.0F : (now - lastFrameNanos) / 1.0E9F;
        lastFrameNanos = now;

        float duration = (float) Config.GIPFAELI_ZOOM_SECONDS.getAsDouble();
        if (duration <= 0.0F || seconds <= 0.0F) {
            return towards;
        }

        // Clamped so a stutter or a paused game cannot jump the whole way in one frame.
        float blend = 1.0F - (float) Math.exp(-Math.min(seconds, 0.1F) / duration);
        float eased = current + (towards - current) * blend;
        return Math.abs(towards - eased) < 0.001F ? towards : eased;
    }
}
