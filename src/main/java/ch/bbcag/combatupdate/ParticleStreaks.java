package ch.bbcag.combatupdate;

import java.util.function.DoubleToIntFunction;

import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

// Lines, arcs and rings of light drawn from the server, for the Terraria weapons' slashes and blasts.
//
// Built from vanilla's trail particle, which glides from where it is spawned to a target it is handed
// and glows at full brightness on the way. Laid down along a line with each one aimed a little further
// along it, a row of them reads as a cut travelling down the line rather than a row of dots.
//
// Every particle is a packet of its own, since each has its own target, so these are for moments - a
// swing, a hit - and not for anything drawn every tick.
public final class ParticleStreaks {
    // How far along the line or arc each particle travels before it fades, as a share of the whole.
    private static final double SWEEP = 0.3;

    private ParticleStreaks() {
    }

    // The Exoblade's colours: round the spectrum as t runs from 0 to 1.
    public static int rainbow(double t) {
        return Mth.hsvToRgb((float) (t - Math.floor(t)), 0.65F, 1.0F) & 0xFFFFFF;
    }

    public static DoubleToIntFunction solid(int color) {
        return t -> color;
    }

    // A straight cut from one point to another. Colour is picked by how far along the line (0 to 1).
    public static void line(ServerLevel level, Vec3 from, Vec3 to, int points, int duration, DoubleToIntFunction color) {
        Vec3 span = to.subtract(from);
        for (int i = 0; i < points; i++) {
            double t = points == 1 ? 0.0 : i / (double) (points - 1);
            send(level, from.add(span.scale(t)), from.add(span.scale(Math.min(1.0, t + SWEEP))), color.applyAsInt(t), duration);
        }
    }

    // A swing: an arc round centre, in the plane of a and b (unit vectors at right angles), from angle
    // start to angle end in radians, where 0 points along a and a quarter turn along b. It sweeps the
    // way it is drawn, from start towards end.
    public static void arc(ServerLevel level, Vec3 center, Vec3 a, Vec3 b, double radius,
            double start, double end, int points, int duration, DoubleToIntFunction color) {
        double lead = (end - start) * SWEEP;
        for (int i = 0; i < points; i++) {
            double t = points == 1 ? 0.0 : i / (double) (points - 1);
            double angle = start + (end - start) * t;
            send(level, onCircle(center, a, b, radius, angle), onCircle(center, a, b, radius, angle + lead),
                    color.applyAsInt(t), duration);
        }
    }

    // A shockwave: a ring that bursts outwards from centre to the given radius, square to normal.
    public static void ring(ServerLevel level, Vec3 center, Vec3 normal, double radius, int points, int duration,
            DoubleToIntFunction color) {
        Vec3 a = perpendicular(normal);
        Vec3 b = normal.normalize().cross(a);
        for (int i = 0; i < points; i++) {
            double t = i / (double) points;
            send(level, onCircle(center, a, b, radius * 0.15, t * Mth.TWO_PI),
                    onCircle(center, a, b, radius, t * Mth.TWO_PI), color.applyAsInt(t), duration);
        }
    }

    // Some unit vector at right angles to the one given.
    public static Vec3 perpendicular(Vec3 direction) {
        Vec3 unit = direction.normalize();
        Vec3 other = Math.abs(unit.y) < 0.9 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
        return unit.cross(other).normalize();
    }

    private static Vec3 onCircle(Vec3 center, Vec3 a, Vec3 b, double radius, double angle) {
        return center.add(a.scale(Math.cos(angle) * radius)).add(b.scale(Math.sin(angle) * radius));
    }

    // One particle gliding from one point to another over the given number of ticks.
    public static void send(ServerLevel level, Vec3 at, Vec3 target, int color, int duration) {
        level.sendParticles(new TrailParticleOption(target, color, duration), at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
    }
}
