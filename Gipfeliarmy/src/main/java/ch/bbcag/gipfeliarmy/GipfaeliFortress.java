package ch.bbcag.gipfeliarmy;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.gipfeliarmy.entity.GipfaeliSoldier;

// A fortress out of nothing, with the guards to go with it: /gipfaeliarmy fortress. Stone-brick
// walls with battlements around whoever asked for it, a tower on each corner with a banner on
// top, a gate on the side they are facing, torches enough that nothing spawns inside - and a
// guard post in each corner and at the gate, each with its guards already stationed, all of them
// one new squad under a commander standing in the middle of the yard.
//
// Walls are built down to the ground under each column as well as up to one level top, so a
// fortress on a hillside is sunk into the slope rather than floated over it.
public final class GipfaeliFortress {
    private static final int DEFAULT_RADIUS = 12;
    private static final int MIN_RADIUS = 6;
    private static final int MAX_RADIUS = 40;

    private static final int WALL_HEIGHT = 5;
    private static final int TOWER_HEIGHT = 8;
    private static final int TOWER_REACH = 1;
    private static final int GATE_HALF_WIDTH = 1;
    private static final int GATE_HEIGHT = 3;
    private static final int TORCH_SPACING = 4;
    private static final int DIG_IN = 3;

    // Who stands where: two riflemen a corner, and the gate held by a Panzer soldier and an
    // assault trooper, since the gate is where anyone coming is coming.
    private static final int CORNER_GUARDS = 2;
    private static final int POST_RADIUS = 6;

    private GipfaeliFortress() {
    }

    public static int build(ServerPlayer commander, int radius) {
        radius = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
        ServerLevel level = commander.level();
        BlockPos centre = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, commander.blockPosition());
        int base = centre.getY();
        int top = base + WALL_HEIGHT;

        // The gate faces the way the player is looking: an east gate for a player facing east.
        Direction4 gate = Direction4.facing(commander.getYRot());

        BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState chiseled = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
        BlockState banner = Blocks.WOOL.pick(DyeColor.RED).defaultBlockState();
        BlockState torch = Blocks.TORCH.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        // Walls, column by column, with a crenel every other block along the top.
        for (int along = -radius; along <= radius; along++) {
            for (Direction4 side : Direction4.values()) {
                BlockPos foot = side.wall(centre, radius, along);
                boolean corner = Math.abs(along) >= radius - TOWER_REACH;
                boolean gateway = side == gate && Math.abs(along) <= GATE_HALF_WIDTH;
                int wallTop = corner ? base + TOWER_HEIGHT : top;
                column(level, foot, base, wallTop, brick);
                if (gateway) {
                    for (int y = base + 1; y <= base + GATE_HEIGHT; y++) {
                        level.setBlock(new BlockPos(foot.getX(), y, foot.getZ()), air, 3);
                    }

                    level.setBlock(new BlockPos(foot.getX(), base + GATE_HEIGHT + 1, foot.getZ()), chiseled, 3);
                } else if (!corner && along % 2 == 0) {
                    level.setBlock(new BlockPos(foot.getX(), wallTop + 1, foot.getZ()), brick, 3);
                }

                if (!corner && along % TORCH_SPACING == 0 && !gateway) {
                    level.setBlock(new BlockPos(foot.getX(), wallTop + 1, foot.getZ()), torch, 3);
                }
            }
        }

        // Towers: a solid 3x3 on each corner, a banner block on top, a torch beside it.
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                for (int dx = -TOWER_REACH; dx <= TOWER_REACH; dx++) {
                    for (int dz = -TOWER_REACH; dz <= TOWER_REACH; dz++) {
                        BlockPos foot = centre.offset(sx * radius + dx, 0, sz * radius + dz);
                        column(level, foot, base, base + TOWER_HEIGHT, brick);
                    }
                }

                BlockPos crown = centre.offset(sx * radius, base + TOWER_HEIGHT + 1 - centre.getY(), sz * radius);
                level.setBlock(crown, banner, 3);
                level.setBlock(crown.above(), torch, 3);
            }
        }

        // A guard post in each corner of the yard and one inside the gate, numbered in that order.
        List<BlockPos> posts = new ArrayList<>();
        int inset = radius - TOWER_REACH - 2;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                posts.add(post(level, commander, centre.offset(sx * inset, 0, sz * inset)));
            }
        }
        posts.add(post(level, commander, gate.wall(centre, radius - 3, 0)));

        // The garrison: a squad of its own, its commander in the middle of the yard.
        DyeColor colour = GipfaeliArmy.nextSquadColour(commander);
        Vec3 yard = Vec3.atBottomCenterOf(ground(level, centre));
        GipfaeliSoldier leader = GipfaeliArmy.muster(commander, yard, GipfaeliWeapon.RIFLEMAN, colour);
        leader.setCommander(true);
        leader.setHealth(leader.getMaxHealth());
        leader.holdPosition(true);

        int guards = 0;
        for (int index = 0; index < posts.size(); index++) {
            BlockPos post = posts.get(index);
            boolean gatePost = index == posts.size() - 1;
            GipfaeliWeapon[] kits = gatePost
                    ? new GipfaeliWeapon[] {GipfaeliWeapon.PANZER, GipfaeliWeapon.ASSAULT}
                    : new GipfaeliWeapon[] {GipfaeliWeapon.RIFLEMAN, GipfaeliWeapon.RIFLEMAN};
            for (int guard = 0; guard < CORNER_GUARDS; guard++) {
                GipfaeliSoldier soldier = GipfaeliArmy.muster(commander, Vec3.atBottomCenterOf(ground(level, post).above()), kits[guard], colour);
                GipfaeliArmy.post(commander, soldier, post, POST_RADIUS);
                guards++;
            }
        }

        level.playSound(null, commander.getX(), commander.getY(), commander.getZ(),
                SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 1.0F, 0.6F);
        commander.sendSystemMessage(Component.translatable("gipfeliarmy.army.fortress.built",
                radius * 2 + 1, posts.size(), guards, GipfaeliArmy.colourName(colour)).withStyle(ChatFormatting.GOLD));
        GipfaeliArmy.reform(commander);
        return guards;
    }

    // One post block, set down on the ground with the player as its owner, on the same list as
    // one they placed by hand.
    private static BlockPos post(ServerLevel level, ServerPlayer commander, BlockPos at) {
        BlockPos spot = ground(level, at);
        level.setBlock(spot, GipfeliArmyMod.GIPFAELI_GUARD_POST.get().defaultBlockState(), 3);
        if (level.getBlockEntity(spot) instanceof GipfaeliGuardPost.Post post) {
            post.claim(commander);
            post.setRadius(POST_RADIUS);
            GipfaeliArmyData.get(level.getServer()).addPost(commander.getUUID(), level, spot);
        }

        level.setBlock(spot.above(), Blocks.TORCH.defaultBlockState(), 3);
        return spot;
    }

    // The first free block above the ground at this column.
    private static BlockPos ground(ServerLevel level, BlockPos at) {
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at);
    }

    // A column of wall: from a little under the ground at this spot up to the level top, so the
    // wall neither floats over a dip nor leaves a step in the parapet over a rise.
    private static void column(ServerLevel level, BlockPos foot, int base, int top, BlockState state) {
        int ground = ground(level, foot).getY();
        int bottom = Math.min(ground, base) - DIG_IN;
        for (int y = bottom; y <= top; y++) {
            level.setBlock(new BlockPos(foot.getX(), y, foot.getZ()), state, 3);
        }
    }

    // The four walls, and the way to walk along each.
    private enum Direction4 {
        NORTH, EAST, SOUTH, WEST;

        static Direction4 facing(float yaw) {
            int quarter = Math.floorMod(Math.round(yaw / 90.0F), 4);
            return switch (quarter) {
                case 0 -> SOUTH;
                case 1 -> WEST;
                case 2 -> NORTH;
                default -> EAST;
            };
        }

        // The block on this wall, radius out from the centre and along steps along it.
        BlockPos wall(BlockPos centre, int radius, int along) {
            return switch (this) {
                case NORTH -> centre.offset(along, 0, -radius);
                case SOUTH -> centre.offset(along, 0, radius);
                case EAST -> centre.offset(radius, 0, along);
                case WEST -> centre.offset(-radius, 0, along);
            };
        }
    }

    public static int defaultRadius(@Nullable Integer wanted) {
        return wanted == null ? DEFAULT_RADIUS : wanted;
    }
}
