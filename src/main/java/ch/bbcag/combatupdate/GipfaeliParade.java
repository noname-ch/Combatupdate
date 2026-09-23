package ch.bbcag.combatupdate;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// Where a paraded squad stands: ranks and files in a square block on the ground, in front of the
// commander or behind them.
//
// This is not GipfaeliFormation. That one is a shape a walking squad keeps around a commander who
// moves, worked out fresh every tick; this is a set of spots a squad is put down on once and then
// holds. A parade is a thing you stand and look at, so it is measured from where the commander was
// standing when they called it and stays there afterwards.
//
// Past one block's worth of soldiers the next block falls in beside the first with a gap between
// them, the way a second company forms up next to the first rather than making the first one
// deeper. The whole parade stays centred on the commander's line however many blocks that comes to.
public final class GipfaeliParade {
    // How far a ray looks for ground above and below the commander's own feet. Enough for a parade
    // called on a hillside or off the edge of a wall, not so much that a soldier lands in the
    // valley below when the block it was meant for is missing.
    private static final double GROUND_ABOVE = 3.0;
    private static final double GROUND_BELOW = 5.0;

    private GipfaeliParade() {
    }

    // How many soldiers stand in one block of the parade, which is also how many go down before a
    // second block is started.
    public static int perBlock() {
        return width() * depth();
    }

    // Where soldier number index of size stands, with the commander at anchor facing yaw. Ahead
    // puts the parade out in front of them; otherwise it forms up at their back.
    //
    // Filled a rank at a time from the front: the first soldiers in are the front rank, so a parade
    // of three is a short line rather than a column of one.
    public static Vec3 slot(int index, int size, Vec3 anchor, float yaw, boolean ahead) {
        int width = width();
        int depth = depth();
        double spacing = Config.ARMY_PARADE_SPACING.getAsDouble();

        int perBlock = width * depth;
        int block = index / perBlock;
        int within = index % perBlock;
        int rank = within / width;
        int file = within % width;

        // One block of soldiers plus the gap to the next one, so blocks read as separate companies
        // rather than as one very wide crowd.
        double blockPitch = width * spacing + Config.ARMY_PARADE_BLOCK_GAP.getAsDouble();
        int blocks = Math.max(1, (size + perBlock - 1) / perBlock);

        double across = (block - (blocks - 1) / 2.0) * blockPitch
                + (file - (width - 1) / 2.0) * spacing;
        double back = Config.ARMY_PARADE_STANDOFF.getAsDouble() + rank * spacing;

        Vec3 forward = Vec3.directionFromRotation(0.0F, yaw);
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);

        return anchor.add(forward.scale(ahead ? back : -back)).add(right.scale(across));
    }

    // The same spot, dropped onto whatever it is standing over. A parade ground is rarely flat, and
    // a soldier spawned in the air falls out of its rank on the way down.
    public static Vec3 onGround(ServerLevel level, Entity commander, Vec3 spot) {
        BlockHitResult hit = level.clip(new ClipContext(
                spot.add(0.0, GROUND_ABOVE, 0.0), spot.subtract(0.0, GROUND_BELOW, 0.0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, commander));

        // Nothing solid under it at all: leave it where the rank says, rather than dropping it into
        // a hole it was never meant to be standing in.
        return hit.getType() == HitResult.Type.MISS ? spot : new Vec3(spot.x, hit.getLocation().y, spot.z);
    }

    private static int width() {
        return Config.ARMY_PARADE_WIDTH.getAsInt();
    }

    private static int depth() {
        return Config.ARMY_PARADE_DEPTH.getAsInt();
    }
}
