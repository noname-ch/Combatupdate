package ch.bbcag.combatupdate.entity;

import net.minecraft.world.phys.Vec3;

// The vector maths a homing projectile steers with: a bounded turn of one direction towards another.
// The Exobeam and the Scarlet bullet both bend towards what they chase this way. It is the same
// arithmetic the Gipfaeli Army mod's rocket flies on, kept as a copy here so neither mod has to
// load the other for it.
public final class Steering {
    private static final double EPSILON = 1.0E-6;

    private Steering() {
    }

    // Rodrigues' rotation: turns a vector about an arbitrary axis, which the two frames above both need.
    private static Vec3 rotateAbout(Vec3 vector, Vec3 axis, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return vector.scale(cos)
                .add(axis.cross(vector).scale(sin))
                .add(axis.scale(axis.dot(vector) * (1.0 - cos)));
    }

    // Rotates heading the given angle towards desired, in the plane the two of them span.
    public static Vec3 turn(Vec3 heading, Vec3 desired, double radians) {
        // The part of the target direction at right angles to where we are pointing: the direction the
        // turn has to go in. It vanishes when the two are exactly in line, which leaves nothing to
        // turn towards and no turn to make.
        Vec3 sideways = desired.subtract(heading.scale(heading.dot(desired)));
        if (sideways.lengthSqr() < EPSILON * EPSILON) {
            return heading;
        }

        return heading.scale(Math.cos(radians))
                .add(sideways.normalize().scale(Math.sin(radians)));
    }
}
