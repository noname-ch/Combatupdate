package ch.bbcag.gipfeliarmy.terraria;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import ch.bbcag.gipfeliarmy.Config;
import ch.bbcag.gipfeliarmy.GipfeliArmyMod;

// Where each boss lives, in the overworld of this world.
//
// Every lair is picked once, the first time the server runs with the bosses on: a random spot
// somewhere between a few hundred blocks and Config.TERRARIA_MAX_DISTANCE from world spawn, seeded
// off the world seed so the same seed puts them in the same places. Only the spot is picked then.
// The lair itself is built the first time a player comes near enough for its chunks to be loaded,
// because nothing can be built into a chunk that has not been generated - and that is also the
// first moment anyone knows how high the ground there is.
//
// A boss wakes when a player walks into its lair, and is never saved with the world: walk away
// and it goes back to sleep, to wake at full health next time. Once it is killed, it can be woken
// again after Config.TERRARIA_RESPAWN_MINUTES.
public final class BossArenas extends SavedData {
    // Nothing closer to spawn than this, so a lair is somewhere to go rather than something the
    // player trips over while building their first house.
    private static final int MIN_DISTANCE = 350;
    // Nor closer to another lair, so a trip to one is not a trip to all three.
    private static final int MIN_SEPARATION = 300;
    // How near a player has to come, horizontally, before a lair's chunks are worth checking.
    private static final int BUILD_RANGE = 96;
    // How far out from a lair a sleeping boss is looked for, so the same one is not woken twice.
    private static final int BOSS_SEARCH = 160;

    private static final int NOT_KNOWN = Integer.MIN_VALUE;

    public static final class Arena {
        private static final Codec<Arena> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("kind").forGetter(arena -> arena.kind.ordinal()),
                Codec.INT.fieldOf("x").forGetter(arena -> arena.x),
                Codec.INT.fieldOf("z").forGetter(arena -> arena.z),
                Codec.INT.optionalFieldOf("y", NOT_KNOWN).forGetter(arena -> arena.y),
                Codec.INT.optionalFieldOf("inner", NOT_KNOWN).forGetter(arena -> arena.inner),
                Codec.LONG.optionalFieldOf("readyAt", 0L).forGetter(arena -> arena.readyAt),
                Codec.INT.optionalFieldOf("defeats", 0).forGetter(arena -> arena.defeats))
                .apply(instance, (kind, x, z, y, inner, readyAt, defeats) -> {
                    Arena arena = new Arena(BossKind.byOrdinal(kind), x, z);
                    arena.y = y;
                    arena.inner = inner;
                    arena.readyAt = readyAt;
                    arena.defeats = defeats;
                    return arena;
                }));

        final BossKind kind;
        // The waypoint: the lair's door on the surface.
        final int x;
        final int z;
        int y = NOT_KNOWN;
        // Where the boss is fought, if not at the door: the floor of the Wall of Flesh's tunnel, or
        // the middle of Plantera's cave. Worked out when the lair is built.
        int inner = NOT_KNOWN;
        long readyAt;
        int defeats;

        Arena(BossKind kind, int x, int z) {
            this.kind = kind;
            this.x = x;
            this.z = z;
        }

        public BossKind kind() {
            return this.kind;
        }

        public boolean built() {
            return this.y != NOT_KNOWN;
        }

        public BlockPos door() {
            return new BlockPos(this.x, this.built() ? this.y : 64, this.z);
        }

        public int inner() {
            return this.inner;
        }

        public int defeats() {
            return this.defeats;
        }

        public long readyAt() {
            return this.readyAt;
        }
    }

    private static final Codec<BossArenas> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Arena.CODEC.listOf().optionalFieldOf("arenas", List.of()).forGetter(data -> data.arenas),
            Codec.INT.optionalFieldOf("generation", 0).forGetter(data -> data.generation))
            .apply(instance, BossArenas::new));

    public static final SavedDataType<BossArenas> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(GipfeliArmyMod.MODID, "terraria_bosses"), BossArenas::new, CODEC);

    private final List<Arena> arenas = new ArrayList<>();
    // How many times the lairs have been relocated by command, so each time picks new spots.
    private int generation;

    public BossArenas() {
    }

    private BossArenas(List<Arena> arenas, int generation) {
        this.arenas.addAll(arenas);
        this.generation = generation;
    }

    public static BossArenas get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<Arena> arenas() {
        return List.copyOf(this.arenas);
    }

    public @Nullable Arena arena(BossKind kind) {
        for (Arena arena : this.arenas) {
            if (arena.kind == kind) {
                return arena;
            }
        }

        return null;
    }

    // Once a second, from the server tick.
    public static void tick(MinecraftServer server) {
        if (!Config.on(Config.ENABLE_TERRARIA_BOSSES) || server.getTickCount() % 20 != 0) {
            return;
        }

        ServerLevel level = server.overworld();
        BossArenas data = get(server);
        if (data.placeMissing(level)) {
            data.broadcast(server);
        }

        boolean changed = false;
        for (Arena arena : data.arenas) {
            ServerPlayer near = nearestPlayer(level, arena.x, arena.z, BUILD_RANGE);
            if (near == null) {
                continue;
            }

            if (!arena.built()) {
                if (ArenaBuilder.canBuild(level, arena)) {
                    ArenaBuilder.build(level, arena);
                    data.setDirty();
                    changed = true;
                }
                continue;
            }

            if (level.getGameTime() < arena.readyAt) {
                continue;
            }

            for (ServerPlayer player : level.players()) {
                if (!player.isSpectator() && ArenaBuilder.inside(arena, player.position()) && !bossAround(level, arena)) {
                    wake(level, arena);
                    break;
                }
            }
        }

        if (changed) {
            data.broadcast(server);
        }
    }

    // Picks a spot for every boss that does not have one yet. True if one was picked.
    private boolean placeMissing(ServerLevel level) {
        boolean placed = false;
        BlockPos spawn = level.getRespawnData().globalPos().pos();
        int maxDistance = Math.max(MIN_DISTANCE + 50, Config.TERRARIA_MAX_DISTANCE.getAsInt() - ArenaBuilder.MAX_REACH);
        RandomSource random = RandomSource.create(level.getSeed() ^ 0x7E44A41A_C7401FL ^ this.generation * 0x9E3779B97F4A7C15L);
        for (BossKind kind : BossKind.values()) {
            // Drawn for every kind whether or not it is placed yet, so a lair that already exists
            // does not change where the next one is put.
            List<int[]> candidates = new ArrayList<>();
            for (int attempt = 0; attempt < 64; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double distance = MIN_DISTANCE + random.nextDouble() * (maxDistance - MIN_DISTANCE);
                candidates.add(new int[] {
                        spawn.getX() + (int) Math.round(Math.cos(angle) * distance),
                        spawn.getZ() + (int) Math.round(Math.sin(angle) * distance)});
            }
            if (this.arena(kind) != null) {
                continue;
            }

            int[] chosen = candidates.get(0);
            for (int[] candidate : candidates) {
                if (this.farFromOthers(candidate[0], candidate[1])) {
                    chosen = candidate;
                    break;
                }
            }

            this.arenas.add(new Arena(kind, chosen[0], chosen[1]));
            GipfeliArmyMod.LOGGER.info("The {} will live at {}, {}", kind.id(), chosen[0], chosen[1]);
            placed = true;
        }

        if (placed) {
            this.setDirty();
        }
        return placed;
    }

    private boolean farFromOthers(int x, int z) {
        for (Arena arena : this.arenas) {
            long dx = arena.x - x;
            long dz = arena.z - z;
            if (dx * dx + dz * dz < (long) MIN_SEPARATION * MIN_SEPARATION) {
                return false;
            }
        }

        return true;
    }

    private static @Nullable ServerPlayer nearestPlayer(ServerLevel level, int x, int z, int range) {
        ServerPlayer nearest = null;
        double best = (double) range * range;
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - x;
            double dz = player.getZ() - z;
            double distance = dx * dx + dz * dz;
            if (distance <= best) {
                best = distance;
                nearest = player;
            }
        }

        return nearest;
    }

    private static boolean bossAround(ServerLevel level, Arena arena) {
        BlockPos door = arena.door();
        AABB around = new AABB(door).inflate(BOSS_SEARCH, 256, BOSS_SEARCH);
        return !level.getEntitiesOfClass(TerrariaBoss.class, around, boss -> boss.kind() == arena.kind && boss.isAlive()).isEmpty();
    }

    private static void wake(ServerLevel level, Arena arena) {
        TerrariaBoss boss = arena.kind.type().create(level, EntitySpawnReason.EVENT);
        if (boss == null) {
            return;
        }

        Vec3 at = ArenaBuilder.bossStart(arena);
        boss.setPos(at);
        boss.setArena(arena);
        boss.setHealth(boss.getMaxHealth());
        level.addFreshEntity(boss);

        // In Terraria's own words, and in its own colour.
        Component message = Component.translatable("gipfeliarmy.terraria.awoken", arena.kind.displayName())
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD);
        for (ServerPlayer player : level.players()) {
            if (player.position().distanceToSqr(at) < 200.0 * 200.0) {
                player.sendSystemMessage(message);
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6F, 0.8F);
            }
        }
    }

    // A boss has fallen: it sleeps for the configured time, and every client is told when it wakes.
    public void defeated(MinecraftServer server, BossKind kind) {
        Arena arena = this.arena(kind);
        if (arena == null) {
            return;
        }

        arena.defeats++;
        arena.readyAt = server.overworld().getGameTime() + Config.TERRARIA_RESPAWN_MINUTES.getAsInt() * 60L * 20L;
        this.setDirty();
        this.broadcast(server);
    }

    // Wakes every boss that is sleeping off a defeat.
    public void readyAll(MinecraftServer server) {
        for (Arena arena : this.arenas) {
            arena.readyAt = 0L;
        }
        this.setDirty();
        this.broadcast(server);
    }

    // Forgets every lair, so the next tick picks them all afresh somewhere else. The blocks already
    // built stay where they are.
    public void reset(MinecraftServer server) {
        this.arenas.clear();
        this.generation++;
        this.setDirty();
        this.broadcast(server);
    }

    public TerrariaNetwork.Waypoints waypoints() {
        List<TerrariaNetwork.Waypoint> waypoints = new ArrayList<>();
        for (Arena arena : this.arenas) {
            waypoints.add(new TerrariaNetwork.Waypoint(arena.kind, arena.x, arena.built() ? arena.y : 0, arena.z,
                    arena.built(), arena.readyAt));
        }

        return new TerrariaNetwork.Waypoints(waypoints);
    }

    public void broadcast(MinecraftServer server) {
        TerrariaNetwork.Waypoints payload = Config.on(Config.ENABLE_TERRARIA_BOSSES)
                ? this.waypoints()
                : new TerrariaNetwork.Waypoints(List.of());
        PacketDistributor.sendToAllPlayers(payload);
    }

    public void send(ServerPlayer player) {
        TerrariaNetwork.Waypoints payload = Config.on(Config.ENABLE_TERRARIA_BOSSES)
                ? this.waypoints()
                : new TerrariaNetwork.Waypoints(List.of());
        PacketDistributor.sendToPlayer(player, payload);
    }
}
