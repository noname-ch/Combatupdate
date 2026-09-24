package ch.bbcag.gipfeliarmy.terraria.client;

import java.util.List;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.gipfeliarmy.Config;
import ch.bbcag.gipfeliarmy.terraria.TerrariaNetwork;

// A waypoint on the HUD for every boss lair: a diamond in the boss's colour over the spot, with the
// lair's name and how far off it is. A lair that is off screen is pinned to the edge of it, on the
// side you would have to turn to, so the way there is never lost. A lair whose boss is sleeping off
// a defeat is drawn grey, with how long it has left.
//
// Placed by projecting the lair into screen space by hand, the way GipfaeliSight places its
// reticle, for the same reasons given there.
final class WaypointHud {
    static volatile List<TerrariaNetwork.Waypoint> waypoints = List.of();
    static float renderFov = 70.0F;

    private static final int EDGE_MARGIN = 14;
    private static final int DIAMOND = 5;
    private static final int SLEEPING_COLOUR = 0xFF8A8A8A;
    private static final int OUTLINE = 0xC0000000;
    private static final double MIN_DEPTH = 0.05;

    private WaypointHud() {
    }

    static void render(GuiGraphicsExtractor graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        List<TerrariaNetwork.Waypoint> all = waypoints;
        if (all.isEmpty() || minecraft.level == null || minecraft.player == null
                || !Config.on(Config.ENABLE_TERRARIA_BOSSES) || !Config.TERRARIA_SHOW_WAYPOINTS.get()
                || minecraft.level.dimension() != Level.OVERWORLD) {
            return;
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 eye = camera.position();
        Vec3 forward = Vec3.directionFromRotation(camera.xRot(), camera.yRot());
        Vec3 up = Vec3.directionFromRotation(camera.xRot() - 90.0F, camera.yRot());
        Vec3 right = forward.cross(up);

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        double scale = (height / 2.0) / Math.tan(Math.toRadians(renderFov) / 2.0);
        long now = minecraft.level.getGameTime();
        Font font = minecraft.font;

        for (TerrariaNetwork.Waypoint waypoint : all) {
            // A lair that has not been built yet has no height; it is drawn level with the eye.
            Vec3 at = new Vec3(waypoint.x() + 0.5, waypoint.yKnown() ? waypoint.y() + 1.5 : eye.y, waypoint.z() + 0.5);
            Vec3 relative = at.subtract(eye);
            double depth = relative.dot(forward);
            double across = relative.dot(right);
            double lift = relative.dot(up);

            double screenX;
            double screenY;
            boolean pinned;
            if (depth > MIN_DEPTH) {
                screenX = width / 2.0 + across / depth * scale;
                screenY = height / 2.0 - lift / depth * scale;
                pinned = screenX < EDGE_MARGIN || screenX > width - EDGE_MARGIN || screenY < EDGE_MARGIN || screenY > height - EDGE_MARGIN;
            } else {
                screenX = 0;
                screenY = 0;
                pinned = true;
            }

            if (pinned) {
                // Pushed out from the middle of the screen, in the direction of the lair, until it
                // meets the edge. Straight behind is taken as below, like a compass would.
                double dx = across;
                double dy = -lift;
                if (depth <= MIN_DEPTH && Math.abs(dx) < 1.0E-3 && Math.abs(dy) < 1.0E-3) {
                    dy = 1.0;
                }
                double halfW = width / 2.0 - EDGE_MARGIN;
                double halfH = height / 2.0 - EDGE_MARGIN;
                double stretch = Math.min(Math.abs(dx) < 1.0E-6 ? Double.MAX_VALUE : halfW / Math.abs(dx),
                        Math.abs(dy) < 1.0E-6 ? Double.MAX_VALUE : halfH / Math.abs(dy));
                screenX = width / 2.0 + dx * stretch;
                screenY = height / 2.0 + dy * stretch;
            }

            boolean sleeping = waypoint.readyAt() > now;
            int colour = sleeping ? SLEEPING_COLOUR : waypoint.kind().colour();
            int x = (int) Math.round(screenX);
            int y = (int) Math.round(screenY);
            diamond(graphics, x, y, DIAMOND + 1, OUTLINE);
            diamond(graphics, x, y, DIAMOND, colour);

            double distance = Math.sqrt(relative.x * relative.x + relative.z * relative.z);
            Component label = sleeping
                    ? Component.translatable("gipfeliarmy.terraria.waypoint.sleeping", waypoint.kind().lairName(),
                            (int) Math.round(distance), (waypoint.readyAt() - now) / 1200 + 1)
                    : Component.translatable("gipfeliarmy.terraria.waypoint", waypoint.kind().lairName(), (int) Math.round(distance));
            int textWidth = font.width(label);
            int textX = Math.clamp(x - textWidth / 2, 2, Math.max(2, width - textWidth - 2));
            int textY = y > height - EDGE_MARGIN - 12 ? y - DIAMOND - 11 : y + DIAMOND + 3;
            graphics.fill(textX - 2, textY - 1, textX + textWidth + 1, textY + 9, 0x70000000);
            graphics.text(font, label, textX, textY, colour | 0xFF000000, false);
        }
    }

    // A filled diamond, one row at a time.
    private static void diamond(GuiGraphicsExtractor graphics, int x, int y, int radius, int colour) {
        for (int row = -radius; row <= radius; row++) {
            int half = radius - Math.abs(row);
            graphics.fill(x - half, y + row, x + half + 1, y + row + 1, colour);
        }
    }
}
