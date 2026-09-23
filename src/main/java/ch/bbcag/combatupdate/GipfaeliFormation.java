package ch.bbcag.combatupdate;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.phys.Vec3;

// The shapes a squad can be told to stand in, and where each soldier in it belongs.
//
// A formation is worked out fresh every time it is asked for, from where the commander is standing
// and which way they face, so it walks and turns with them rather than being a set of spots on the
// ground. Each soldier knows only its own number in the line (see GipfaeliSoldier) and asks here
// where that number stands now.
public enum GipfaeliFormation {
    // No shape at all: vanilla's follow-the-owner, which bunches up behind you like dogs.
    LOOSE,
    // A rank abreast, two and a half blocks back, so the whole squad has a clear field of fire
    // past you and past each other.
    LINE,
    // An arrowhead with its point at your back: the classic shape for walking into something.
    WEDGE,
    // Single file. The one for narrow paths, tunnels and bridges, where a line would have half the
    // squad pathing round through the wall.
    COLUMN,
    // A ring around you, facing out. Nothing gets to you without walking through a soldier.
    CIRCLE,
    // On parade: companies five abreast and ten deep, a pace apart, two paces of clear ground
    // between companies, side by side and then rank behind rank. The shape a squad stands in when
    // it has been told to stand, and the shape it marches in when told to follow that way.
    PARADE;

    // The commander stands this many paces in front of the first rank.
    private static final double COMMANDER_LEAD = 2.0;

    // Marks the commander's place in a block rather than a soldier's.
    public static final int COMMANDER_SLOT = -1;

    private static final GipfaeliFormation[] ALL = values();

    // How far back the first rank stands, so a soldier is never underfoot.
    private static final double STANDOFF = 2.5;
    private static final double SPACING = 1.6;

    public static GipfaeliFormation byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < ALL.length ? ALL[ordinal] : LOOSE;
    }

    public static @Nullable GipfaeliFormation byName(String name) {
        for (GipfaeliFormation formation : ALL) {
            if (formation.token().equalsIgnoreCase(name)) {
                return formation;
            }
        }

        return null;
    }

    // The word the command and the menu's buttons use for it.
    public String token() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    public String key() {
        return "combatupdate.army.formation." + this.token();
    }

    // On parade: where soldier number index of block number block, of blocks in all, stands, with
    // whoever called the parade at anchor facing yaw. Each squad is one block - as many abreast
    // and as deep as the config says, five and eight by default - and the blocks stand side by
    // side with clear ground between them. The squad's commander (COMMANDER_SLOT) stands out in
    // front of its block, on the middle file, a couple of paces ahead of the first rank.
    public static Vec3 parade(int block, int blocks, int index, Vec3 anchor, float yaw) {
        int width = Config.ARMY_PARADE_WIDTH.getAsInt();
        double pace = Config.ARMY_PARADE_SPACING.getAsDouble();
        double standoff = Config.ARMY_PARADE_STANDOFF.getAsDouble();
        double blockPitch = width * pace + Config.ARMY_PARADE_BLOCK_GAP.getAsDouble();

        Vec3 forward = Vec3.directionFromRotation(0.0F, yaw);
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);

        double blockAcross = (block - (blocks - 1) / 2.0) * blockPitch;
        if (index == COMMANDER_SLOT) {
            return anchor.subtract(forward.scale(standoff)).add(right.scale(blockAcross));
        }

        int rank = index / width;
        int file = index % width;
        double across = blockAcross + (file - (width - 1) / 2.0) * pace;
        double back = standoff + COMMANDER_LEAD * pace + rank * pace;
        return anchor.subtract(forward.scale(back)).add(right.scale(across));
    }

    // Where soldier number index of size stands, given the commander at anchor facing yaw. Null for
    // LOOSE, which has no spots to hand out.
    public @Nullable Vec3 slot(int index, int size, Vec3 anchor, float yaw) {
        if (this == LOOSE || size <= 0) {
            return null;
        }

        Vec3 forward = Vec3.directionFromRotation(0.0F, yaw);
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);

        return switch (this) {
            case LINE -> {
                double across = (index - (size - 1) / 2.0) * SPACING;
                yield anchor.subtract(forward.scale(STANDOFF)).add(right.scale(across));
            }
            case WEDGE -> {
                // Number 0 is the point; every pair after it is one rank further back and one step
                // further out on either side.
                int rank = (index + 1) / 2;
                int side = index == 0 ? 0 : (index % 2 == 1 ? -1 : 1);
                yield anchor.subtract(forward.scale(STANDOFF + rank * SPACING))
                        .add(right.scale(side * rank * SPACING));
            }
            case COLUMN -> anchor.subtract(forward.scale(STANDOFF + index * SPACING * 0.9));
            case CIRCLE -> {
                // Wide enough that a big squad is not stood in each other's laps, and never so
                // tight that the ring is inside your own hitbox.
                double radius = Math.max(STANDOFF, size * 0.55);
                double angle = Math.PI * 2.0 * index / size;
                yield anchor.subtract(forward.scale(Math.cos(angle) * radius))
                        .add(right.scale(Math.sin(angle) * radius));
            }
            // A parade is drawn up a squad at a time, not a soldier at a time; see parade().
            case PARADE -> parade(0, 1, index, anchor, yaw);
            case LOOSE -> null;
        };
    }
}
