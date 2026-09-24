package ch.bbcag.gipfeliarmy.terraria;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

// The three lairs, built block by block the first time a player comes near one (see BossArenas).
// Each is laid out the same way every time but dressed at random - which pillars stand, where the
// moss grows - off a seed taken from where it stands, so no two worlds' lairs look alike.
//
//  - The Eye of Cthulhu's altar: a round clearing of crimson on the surface, ringed by broken
//    blackstone pillars, with the altar in the middle. The Eye comes down out of the sky over it.
//  - The Wall of Flesh's underworld: a hellevator - a shaft with a ladder - from a ruined obsidian
//    gate on the surface down into a long, narrow tunnel of netherrack and blackstone. The Wall
//    crawls along it from the far end towards the shaft, filling it from side to side.
//  - Plantera's jungle cave: a mossy, vine-hung shaft down into an overgrown cave with Plantera's
//    pink bulb in the middle of it, which is what she bursts out of.
final class ArenaBuilder {
    // How far any part of a lair reaches from its door, horizontally: the length of the Wall of
    // Flesh's tunnel, which is the longest thing here. BossArenas keeps doors this far inside the
    // distance limit so every block of every lair is inside it.
    static final int MAX_REACH = 100;

    private static final int FLAGS = Block.UPDATE_CLIENTS;

    // Eye of Cthulhu
    private static final int ALTAR_RADIUS = 12;
    private static final int ALTAR_CLEARANCE = 14;
    private static final int ALTAR_TRIGGER = 22;
    private static final int EYE_SPAWN_HEIGHT = 18;

    // Wall of Flesh: the tunnel runs west from the shaft. Interior is 5 wide and 10 high, which is
    // exactly the Wall's size, so there is no way past it.
    static final int TUNNEL_LENGTH = 96;
    static final int TUNNEL_HALF_WIDTH = 2;
    static final int TUNNEL_HEIGHT = 10;
    private static final int TUNNEL_DEPTH = 45;
    private static final int TUNNEL_MAX_FLOOR = 24;

    // Plantera: the cave is off to the west of the shaft, so the shaft comes down at its edge.
    static final int CAVE_OFFSET = 10;
    static final int CAVE_RADIUS = 14;
    static final int CAVE_HALF_HEIGHT = 9;
    private static final int CAVE_DEPTH = 32;
    private static final int CAVE_MAX_CENTRE = 40;

    private ArenaBuilder() {
    }

    // Every chunk a lair touches has to be there before it can be built.
    static boolean canBuild(ServerLevel level, BossArenas.Arena arena) {
        int x = arena.x;
        int z = arena.z;
        return switch (arena.kind) {
            case EYE_OF_CTHULHU -> loaded(level, x - ALTAR_RADIUS, z - ALTAR_RADIUS, x + ALTAR_RADIUS, z + ALTAR_RADIUS);
            case WALL_OF_FLESH -> loaded(level, x - TUNNEL_LENGTH - 2, z - 4, x + 4, z + 4);
            case PLANTERA -> loaded(level, x - CAVE_OFFSET - CAVE_RADIUS - 4, z - CAVE_RADIUS - 4,
                    x - CAVE_OFFSET + CAVE_RADIUS + 4, z + CAVE_RADIUS + 4);
        };
    }

    private static boolean loaded(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX; x <= maxX + 15; x += 16) {
            for (int z = minZ; z <= maxZ + 15; z += 16) {
                if (!level.isLoaded(new BlockPos(Math.min(x, maxX), 0, Math.min(z, maxZ)))) {
                    return false;
                }
            }
        }

        return true;
    }

    static void build(ServerLevel level, BossArenas.Arena arena) {
        RandomSource random = RandomSource.create(level.getSeed() * 31L + arena.x * 341873128712L + arena.z * 132897987541L);
        switch (arena.kind) {
            case EYE_OF_CTHULHU -> buildAltar(level, arena, random);
            case WALL_OF_FLESH -> buildUnderworld(level, arena, random);
            case PLANTERA -> buildJungle(level, arena, random);
        }
    }

    // Whether someone standing here has walked into the lair, which is what wakes its boss.
    static boolean inside(BossArenas.Arena arena, Vec3 pos) {
        return switch (arena.kind) {
            case EYE_OF_CTHULHU -> {
                double dx = pos.x - (arena.x + 0.5);
                double dz = pos.z - (arena.z + 0.5);
                yield dx * dx + dz * dz <= ALTAR_TRIGGER * ALTAR_TRIGGER && pos.y > arena.inner - 8 && pos.y < arena.inner + 48;
            }
            case WALL_OF_FLESH -> pos.x >= arena.x - TUNNEL_LENGTH + 1 && pos.x < arena.x + TUNNEL_HALF_WIDTH + 1
                    && pos.z >= arena.z - TUNNEL_HALF_WIDTH && pos.z < arena.z + TUNNEL_HALF_WIDTH + 1
                    && pos.y >= arena.inner && pos.y < arena.inner + TUNNEL_HEIGHT + 1;
            case PLANTERA -> {
                double dx = pos.x - (arena.x - CAVE_OFFSET + 0.5);
                double dz = pos.z - (arena.z + 0.5);
                yield dx * dx + dz * dz <= (CAVE_RADIUS + 2) * (CAVE_RADIUS + 2) && Math.abs(pos.y - arena.inner) < CAVE_HALF_HEIGHT + 2;
            }
        };
    }

    // Where the boss appears when it wakes.
    static Vec3 bossStart(BossArenas.Arena arena) {
        return switch (arena.kind) {
            case EYE_OF_CTHULHU -> new Vec3(arena.x + 0.5, arena.inner + EYE_SPAWN_HEIGHT, arena.z + 0.5);
            // At the far end, filling the tunnel; WallOfFlesh#start says exactly where.
            case WALL_OF_FLESH -> new Vec3(WallOfFlesh.startX(arena.x), arena.inner + 1, arena.z + 0.5);
            case PLANTERA -> new Vec3(arena.x - CAVE_OFFSET + 0.5, arena.inner - 6, arena.z + 0.5);
        };
    }

    // The middle of what the boss is kept to, for the bosses that are kept to anything.
    static Vec3 home(BlockPos door, int inner, BossKind kind) {
        return switch (kind) {
            case EYE_OF_CTHULHU -> new Vec3(door.getX() + 0.5, inner + 1, door.getZ() + 0.5);
            case WALL_OF_FLESH -> new Vec3(door.getX() + 0.5, inner + 1, door.getZ() + 0.5);
            case PLANTERA -> new Vec3(door.getX() - CAVE_OFFSET + 0.5, inner, door.getZ() + 0.5);
        };
    }

    // --- The Eye of Cthulhu's altar -----------------------------------------------------------

    private static void buildAltar(ServerLevel level, BossArenas.Arena arena, RandomSource random) {
        int cx = arena.x;
        int cz = arena.z;
        int top = surface(level, cx, cz);

        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState nylium = Blocks.CRIMSON_NYLIUM.defaultBlockState();
        BlockState wart = Blocks.NETHER_WART_BLOCK.defaultBlockState();
        BlockState netherrack = Blocks.NETHERRACK.defaultBlockState();

        // The clearing: level ground of crimson, open to the sky, ragged at the edge.
        for (int dx = -ALTAR_RADIUS; dx <= ALTAR_RADIUS; dx++) {
            for (int dz = -ALTAR_RADIUS; dz <= ALTAR_RADIUS; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > ALTAR_RADIUS - random.nextFloat() * 1.5F) {
                    continue;
                }

                int x = cx + dx;
                int z = cz + dz;
                for (int y = top + 1; y <= top + ALTAR_CLEARANCE; y++) {
                    set(level, x, y, z, air);
                }

                float roll = random.nextFloat();
                set(level, x, top, z, roll < 0.7F ? nylium : roll < 0.85F ? wart : netherrack);
                // Filled down to whatever it stands on, so it is not a lid over a ravine.
                for (int y = top - 1; y > top - 12; y--) {
                    BlockState below = level.getBlockState(new BlockPos(x, y, z));
                    if (y < top - 2 && !below.isAir() && below.getFluidState().isEmpty()) {
                        break;
                    }
                    set(level, x, y, z, netherrack);
                }

                if (distance > 3.5 && roll < 0.7F && random.nextFloat() < 0.14F) {
                    set(level, x, top + 1, z, Blocks.CRIMSON_ROOTS.defaultBlockState());
                }
            }
        }

        // A ring of pillars, most of them broken.
        int pillars = 7;
        for (int i = 0; i < pillars; i++) {
            double angle = i * Math.PI * 2.0 / pillars + random.nextDouble() * 0.3;
            int x = cx + (int) Math.round(Math.cos(angle) * 9.0);
            int z = cz + (int) Math.round(Math.sin(angle) * 9.0);
            int height = 1 + random.nextInt(7);
            for (int y = top + 1; y <= top + height; y++) {
                set(level, x, y, z, random.nextFloat() < 0.3F
                        ? Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultBlockState()
                        : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            }
            if (height >= 5) {
                set(level, x, top + height + 1, z, Blocks.SHROOMLIGHT.defaultBlockState());
            } else {
                // What fell off it.
                set(level, x + random.nextInt(3) - 1, top + 1, z + random.nextInt(3) - 1,
                        Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            }
        }

        // Bones sticking up out of the crimson.
        for (int i = 0; i < 6; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = 4.0 + random.nextDouble() * 6.0;
            int x = cx + (int) Math.round(Math.cos(angle) * distance);
            int z = cz + (int) Math.round(Math.sin(angle) * distance);
            int height = 1 + random.nextInt(3);
            for (int y = top + 1; y <= top + height; y++) {
                set(level, x, y, z, Blocks.BONE_BLOCK.defaultBlockState());
            }
        }

        // The altar: obsidian, a red eye of glass, and candles burning round it.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean corner = dx != 0 && dz != 0;
                set(level, cx + dx, top + 1, cz + dz, corner
                        ? Blocks.CRYING_OBSIDIAN.defaultBlockState()
                        : dx == 0 && dz == 0 ? Blocks.RED_NETHER_BRICKS.defaultBlockState() : Blocks.OBSIDIAN.defaultBlockState());
                if (corner) {
                    set(level, cx + dx, top + 2, cz + dz, Blocks.DYED_CANDLE.pick(DyeColor.RED).defaultBlockState()
                            .setValue(CandleBlock.CANDLES, 1 + random.nextInt(3))
                            .setValue(CandleBlock.LIT, true));
                }
            }
        }
        set(level, cx, top + 2, cz, Blocks.STAINED_GLASS.pick(DyeColor.RED).defaultBlockState());
        set(level, cx, top + 3, cz, Blocks.SHROOMLIGHT.defaultBlockState());

        arena.y = top + 1;
        arena.inner = top;
    }

    // --- The Wall of Flesh's underworld --------------------------------------------------------

    private static void buildUnderworld(ServerLevel level, BossArenas.Arena arena, RandomSource random) {
        int cx = arena.x;
        int cz = arena.z;
        int top = surface(level, cx, cz);
        int floor = Math.max(level.getMinY() + 6, Math.min(top - TUNNEL_DEPTH, TUNNEL_MAX_FLOOR));
        int ceiling = floor + TUNNEL_HEIGHT + 1;

        BlockState air = Blocks.AIR.defaultBlockState();

        // The tunnel: west from the shaft, a shell of hell round a 5x10 hole.
        int west = cx - TUNNEL_LENGTH + 1;
        int east = cx + TUNNEL_HALF_WIDTH;
        for (int x = west - 1; x <= east + 1; x++) {
            for (int dz = -TUNNEL_HALF_WIDTH - 1; dz <= TUNNEL_HALF_WIDTH + 1; dz++) {
                for (int y = floor; y <= ceiling; y++) {
                    boolean shell = x < west || x > east || Math.abs(dz) > TUNNEL_HALF_WIDTH || y == floor || y == ceiling;
                    set(level, x, y, cz + dz, shell ? hellstone(random, y == floor) : air);
                }
            }
        }

        // Light, hanging and set into the roof, and ruins along the walls.
        for (int x = west; x <= east; x++) {
            int along = x - west;
            if (along % 6 == 3) {
                set(level, x, ceiling, cz, Blocks.GLOWSTONE.defaultBlockState());
            }
            if (along % 10 == 5) {
                int side = random.nextBoolean() ? TUNNEL_HALF_WIDTH : -TUNNEL_HALF_WIDTH;
                set(level, x, ceiling - 1, cz + side, Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            }
            if (x < cx - 6 && random.nextFloat() < 0.12F) {
                int side = random.nextBoolean() ? TUNNEL_HALF_WIDTH : -TUNNEL_HALF_WIDTH;
                int height = 1 + random.nextInt(3);
                for (int y = floor + 1; y <= floor + height; y++) {
                    set(level, x, y, cz + side, random.nextFloat() < 0.7F
                            ? Blocks.NETHER_BRICKS.defaultBlockState()
                            : Blocks.OBSIDIAN.defaultBlockState());
                }
            }
            if (random.nextFloat() < 0.05F) {
                set(level, x, floor + 1, cz + random.nextInt(2 * TUNNEL_HALF_WIDTH + 1) - TUNNEL_HALF_WIDTH,
                        Blocks.BONE_BLOCK.defaultBlockState());
            }
        }

        // The hellevator: a 3x3 shaft from the surface into the east end of the tunnel, with a
        // ladder up the north side of it. Where the shaft opens into the tunnel the ladder is
        // carried the rest of the way down on a pillar.
        for (int y = floor + 1; y <= top + 3; y++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                    if (y < ceiling) {
                        // Inside the tunnel: nothing but the pillar the ladder hangs on.
                        if (dx == 0 && dz == -2) {
                            set(level, cx, y, cz - 2, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                        }
                        continue;
                    }
                    if (!wall) {
                        set(level, cx + dx, y, cz + dz, air);
                    } else if (y <= top) {
                        set(level, cx + dx, y, cz + dz, random.nextFloat() < 0.5F
                                ? Blocks.BLACKSTONE.defaultBlockState()
                                : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                    }
                }
            }
            if (y <= top + 1) {
                set(level, cx, y, cz - 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
            }
        }

        // The gate over it: an obsidian frame, half fallen in, with a lantern either side.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) != 2 && Math.abs(dz) != 2) {
                    continue;
                }
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                int height = corner ? 3 + random.nextInt(2) : random.nextFloat() < 0.6F ? 1 : 0;
                for (int y = top + 1; y <= top + height; y++) {
                    set(level, cx + dx, y, cz + dz, random.nextFloat() < 0.25F
                            ? Blocks.CRYING_OBSIDIAN.defaultBlockState()
                            : Blocks.OBSIDIAN.defaultBlockState());
                }
                if (corner) {
                    set(level, cx + dx, top + height + 1, cz + dz, Blocks.SOUL_LANTERN.defaultBlockState());
                }
            }
        }
        // A step up to the ladder, so the shaft can be climbed out of as well as into.
        set(level, cx, top + 1, cz - 2, Blocks.OBSIDIAN.defaultBlockState());

        arena.y = top + 1;
        arena.inner = floor;
    }

    private static BlockState hellstone(RandomSource random, boolean floor) {
        float roll = random.nextFloat();
        if (floor && roll < 0.04F) {
            return Blocks.MAGMA_BLOCK.defaultBlockState();
        }
        if (roll < 0.55F) {
            return Blocks.NETHERRACK.defaultBlockState();
        }
        if (roll < 0.75F) {
            return Blocks.BLACKSTONE.defaultBlockState();
        }
        if (roll < 0.9F) {
            return Blocks.BASALT.defaultBlockState();
        }
        return Blocks.NETHER_BRICKS.defaultBlockState();
    }

    // --- Plantera's jungle cave ----------------------------------------------------------------

    private static void buildJungle(ServerLevel level, BossArenas.Arena arena, RandomSource random) {
        int doorX = arena.x;
        int cz = arena.z;
        int cx = doorX - CAVE_OFFSET;
        int top = surface(level, doorX, cz);
        int cy = Math.max(level.getMinY() + CAVE_HALF_HEIGHT + 6, Math.min(top - CAVE_DEPTH, CAVE_MAX_CENTRE));

        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState leaves = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);

        // The cave: a squashed ball, its edge made lumpy by a wobble per column.
        int reach = CAVE_RADIUS + 3;
        int reachY = CAVE_HALF_HEIGHT + 3;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double wobble = 1.0 + (random.nextDouble() - 0.5) * 0.18;
                int lowest = Integer.MAX_VALUE;
                int highest = Integer.MIN_VALUE;
                for (int dy = -reachY; dy <= reachY; dy++) {
                    double shape = (dx * dx + dz * dz) / (double) (CAVE_RADIUS * CAVE_RADIUS)
                            + dy * dy / (double) (CAVE_HALF_HEIGHT * CAVE_HALF_HEIGHT);
                    shape *= wobble;
                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;
                    if (shape < 1.0) {
                        set(level, x, y, z, air);
                        lowest = Math.min(lowest, y);
                        highest = Math.max(highest, y);
                    } else if (shape < 1.5) {
                        float roll = random.nextFloat();
                        set(level, x, y, z, roll < 0.45F ? Blocks.MOSS_BLOCK.defaultBlockState()
                                : roll < 0.7F ? Blocks.MUD.defaultBlockState()
                                : roll < 0.84F ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                                : roll < 0.97F ? leaves
                                : Blocks.VERDANT_FROGLIGHT.defaultBlockState());
                    }
                }

                if (lowest != Integer.MAX_VALUE) {
                    // A carpet of moss and the odd azalea on the floor...
                    set(level, cx + dx, lowest - 1, cz + dz, Blocks.MOSS_BLOCK.defaultBlockState());
                    float roll = random.nextFloat();
                    if (roll < 0.35F) {
                        set(level, cx + dx, lowest, cz + dz, Blocks.MOSS_CARPET.defaultBlockState());
                    } else if (roll < 0.40F) {
                        set(level, cx + dx, lowest, cz + dz, Blocks.AZALEA.defaultBlockState());
                    } else if (roll < 0.44F) {
                        set(level, cx + dx, lowest, cz + dz, Blocks.FLOWERING_AZALEA.defaultBlockState());
                    }

                    // ...and roots, blossoms and leaves hanging from the roof.
                    roll = random.nextFloat();
                    if (highest - lowest > 3) {
                        if (roll < 0.1F) {
                            set(level, cx + dx, highest, cz + dz, Blocks.HANGING_ROOTS.defaultBlockState());
                        } else if (roll < 0.13F) {
                            set(level, cx + dx, highest, cz + dz, Blocks.SPORE_BLOSSOM.defaultBlockState());
                        } else if (roll < 0.2F) {
                            int length = 1 + random.nextInt(3);
                            for (int y = highest; y > highest - length; y--) {
                                set(level, cx + dx, y, cz + dz, leaves);
                            }
                        }
                    }
                }
            }
        }

        // The bulb, on a mossy stalk, with a crown of flowering azalea.
        int bulbY = cy - 5;
        for (int y = cy - CAVE_HALF_HEIGHT - 1; y < bulbY - 1; y++) {
            set(level, cx, y, cz, Blocks.MOSS_BLOCK.defaultBlockState());
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    double distance = Math.sqrt(dx * dx + dy * dy * 1.2 + dz * dz);
                    if (distance <= 2.3) {
                        BlockState state = random.nextFloat() < 0.2F
                                ? Blocks.CONCRETE.pick(DyeColor.MAGENTA).defaultBlockState()
                                : Blocks.CONCRETE.pick(DyeColor.PINK).defaultBlockState();
                        set(level, cx + dx, bulbY + dy, cz + dz, state);
                    } else if (distance <= 3.0 && dy >= 1) {
                        set(level, cx + dx, bulbY + dy, cz + dz,
                                Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
                    }
                }
            }
        }

        // The shaft down, at the door, with vines up the north side of it. Below the cave's roof
        // the vines carry on down a jungle-wood pillar to the floor.
        int halfAtDoor = (int) Math.floor(CAVE_HALF_HEIGHT * Math.sqrt(Math.max(0.0,
                1.0 - (double) (CAVE_OFFSET * CAVE_OFFSET) / (CAVE_RADIUS * CAVE_RADIUS))));
        int floorAtDoor = cy - halfAtDoor;
        BlockState vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH, true);
        for (int y = floorAtDoor; y <= top + 3; y++) {
            boolean inCave = y < cy + halfAtDoor - 1;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                    if (inCave) {
                        continue;
                    }
                    if (!wall) {
                        set(level, doorX + dx, y, cz + dz, air);
                    } else if (y <= top) {
                        float roll = random.nextFloat();
                        set(level, doorX + dx, y, cz + dz, roll < 0.4F ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
                                : roll < 0.7F ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                                : Blocks.MOSS_BLOCK.defaultBlockState());
                    }
                }
            }
            if (inCave) {
                set(level, doorX, y, cz - 2, Blocks.JUNGLE_LOG.defaultBlockState());
            }
            if (y <= top + 1) {
                set(level, doorX, y, cz - 1, vine);
            }
        }

        // The mouth of it on the surface: jungle-wood posts with leaves on top.
        for (int dx = -2; dx <= 2; dx += 4) {
            for (int dz = -2; dz <= 2; dz += 4) {
                int height = 3 + random.nextInt(2);
                for (int y = top + 1; y <= top + height; y++) {
                    set(level, doorX + dx, y, cz + dz, Blocks.JUNGLE_LOG.defaultBlockState());
                }
                for (int lx = -1; lx <= 1; lx++) {
                    for (int lz = -1; lz <= 1; lz++) {
                        if (random.nextFloat() < 0.8F) {
                            set(level, doorX + dx + lx, top + height + 1, cz + dz + lz, leaves);
                        }
                    }
                }
            }
        }
        set(level, doorX, top + 1, cz - 2, Blocks.MOSSY_STONE_BRICKS.defaultBlockState());

        arena.y = top + 1;
        arena.inner = cy;
    }

    // --- Helpers ------------------------------------------------------------------------------

    // The top solid block of a column, water and all: a lair in the sea is built on the sea.
    private static int surface(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.isOutsideBuildHeight(pos)) {
            level.setBlock(pos, state, FLAGS);
        }
    }
}
