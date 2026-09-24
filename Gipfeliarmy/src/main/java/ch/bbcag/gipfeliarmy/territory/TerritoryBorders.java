package ch.bbcag.gipfeliarmy.territory;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import ch.bbcag.gipfeliarmy.Config;

// Claims drawn into the world: a low wall of coloured dust along the edge of every claim near a
// player, green for their own, red for anyone else's, orange for one being captured. Without it
// the only way to know you have walked onto somebody's land is to try to break something there.
//
// Only the outer edge of a territory is drawn. Two neighbouring chunks of the same owner share no
// wall, so a territory reads as one shape rather than a grid. Each wall stands just inside the
// claim it belongs to, so where two owners' land meets both colours show, side by side, and it is
// plain which side is whose.
//
// Sent from the server to each player alone, as ordinary particles: nothing to install on the
// client beyond the mod, and nobody sees the borders around somebody else's position.
public final class TerritoryBorders {
    // How often the wall is redrawn. Dust lives for about three quarters of a second, so this keeps
    // it standing without doubling up.
    private static final int INTERVAL_TICKS = 10;

    // How far inside its own chunk a wall stands, so two walls on one chunk line do not overlap.
    private static final double INSET = 0.15;

    // Heights above the player's feet the wall is drawn at: a knee-high and a head-high row. It
    // follows the player up and down, so it is in view on a hillside or down a mine alike.
    private static final double[] HEIGHTS = {0.4, 1.4};

    private static final DustParticleOptions OWN = new DustParticleOptions(0x40FF40, 1.0F);
    private static final DustParticleOptions OTHER = new DustParticleOptions(0xFF4040, 1.0F);
    private static final DustParticleOptions CONTESTED = new DustParticleOptions(0xFFA020, 1.0F);

    private TerritoryBorders() {
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % INTERVAL_TICKS != 0) {
            return;
        }
        if (!Config.on(Config.ENABLE_TERRITORY) || !Config.TERRITORY_SHOW_BORDERS.get()) {
            return;
        }
        TerritoryData data = TerritoryData.get(event.getServer());
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            draw(player, data);
        }
    }

    private static void draw(ServerPlayer player, TerritoryData data) {
        ServerLevel level = player.level();
        String dimension = TerritoryClaim.Key.dimensionOf(level);
        double distance = Config.TERRITORY_BORDER_DISTANCE.get();
        int reach = (int) Math.ceil(distance / 16.0);
        ChunkPos here = player.chunkPosition();
        UUID me = player.getUUID();

        for (int cz = here.z() - reach; cz <= here.z() + reach; cz++) {
            for (int cx = here.x() - reach; cx <= here.x() + reach; cx++) {
                TerritoryClaim claim = data.get(new TerritoryClaim.Key(dimension, cx, cz));
                if (claim == null) {
                    continue;
                }
                DustParticleOptions colour = TerritoryManager.contested(claim.key()) ? CONTESTED
                        : claim.ownedBy(me) ? OWN : OTHER;
                double minX = cx * 16.0;
                double minZ = cz * 16.0;

                // Each side is walled off unless the chunk across it belongs to the same owner.
                if (!sameOwner(claim, data.get(new TerritoryClaim.Key(dimension, cx, cz - 1)))) {
                    edge(player, colour, minX, minZ + INSET, true, distance);
                }
                if (!sameOwner(claim, data.get(new TerritoryClaim.Key(dimension, cx, cz + 1)))) {
                    edge(player, colour, minX, minZ + 16.0 - INSET, true, distance);
                }
                if (!sameOwner(claim, data.get(new TerritoryClaim.Key(dimension, cx - 1, cz)))) {
                    edge(player, colour, minX + INSET, minZ, false, distance);
                }
                if (!sameOwner(claim, data.get(new TerritoryClaim.Key(dimension, cx + 1, cz)))) {
                    edge(player, colour, minX + 16.0 - INSET, minZ, false, distance);
                }
            }
        }
    }

    private static boolean sameOwner(TerritoryClaim claim, @Nullable TerritoryClaim neighbour) {
        return neighbour != null && neighbour.owner().equals(claim.owner());
    }

    // One side of a chunk, a particle every block along it, starting at (x, z) and running along
    // x or z. Points further from the player than the configured distance are left out.
    private static void edge(ServerPlayer player, DustParticleOptions colour, double x, double z,
            boolean alongX, double distance) {
        ServerLevel level = player.level();
        double maxSq = distance * distance;
        for (int step = 0; step < 16; step++) {
            double px = alongX ? x + step + 0.5 : x;
            double pz = alongX ? z : z + step + 0.5;
            double dx = px - player.getX();
            double dz = pz - player.getZ();
            if (dx * dx + dz * dz > maxSq) {
                continue;
            }
            for (double height : HEIGHTS) {
                // alwaysShow, so a player who has turned particles down still sees where the
                // borders are; they are not decoration.
                level.sendParticles(player, colour, false, true, px, player.getY() + height, pz, 1, 0, 0, 0, 0);
            }
        }
    }
}
