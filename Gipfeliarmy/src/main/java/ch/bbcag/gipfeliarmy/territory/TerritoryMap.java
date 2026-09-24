package ch.bbcag.gipfeliarmy.territory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import ch.bbcag.gipfeliarmy.territory.TerritoryNetwork.ChunkInfoPayload;
import ch.bbcag.gipfeliarmy.territory.TerritoryNetwork.ChunkInfoPayload.BlockCount;

// Turns a loaded chunk into what the territory screen shows of it: a 16x16 patch of map colours,
// and a tally of what it is built from.
public final class TerritoryMap {
    // How many block types the overview lists. Past this the tail is all single torches.
    private static final int OVERVIEW_ENTRIES = 12;

    private TerritoryMap() {
    }

    // One byte per block column, packed the way vanilla maps pack theirs (colour id and a
    // brightness step), so the client can decode it with MapColor.getColorFromPackedId and the
    // result looks like the map it already knows how to read.
    //
    // Shading follows vanilla's rule of thumb: a column that stands higher than the one north of
    // it is lit, one that sits lower is in shadow, and water darkens with depth.
    public static byte[] render(ServerLevel level, LevelChunk chunk) {
        byte[] out = new byte[256];
        int[] northRow = new int[16];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinY();

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                MapColor color = MapColor.NONE;
                int waterDepth = 0;

                // Walk down from the surface until something with a colour turns up: water is
                // counted through, and colourless things like grass tufts are looked past.
                for (int y = surface; y >= minY; y--) {
                    pos.set(minX + x, y, minZ + z);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.is(Blocks.WATER)) {
                        waterDepth++;
                        continue;
                    }
                    color = state.getMapColor(level, pos);
                    if (color != MapColor.NONE) {
                        break;
                    }
                }

                MapColor.Brightness brightness;
                if (waterDepth > 0) {
                    color = MapColor.WATER;
                    brightness = waterDepth < 3 ? MapColor.Brightness.HIGH
                            : waterDepth < 8 ? MapColor.Brightness.NORMAL : MapColor.Brightness.LOW;
                } else if (z == 0) {
                    brightness = MapColor.Brightness.NORMAL;
                } else {
                    brightness = surface > northRow[x] ? MapColor.Brightness.HIGH
                            : surface < northRow[x] ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                }
                northRow[x] = surface;

                out[z * 16 + x] = (byte) ((color.id << 2) | brightness.id);
            }
        }
        return out;
    }

    // Counts every non-air block in the chunk by type. Read straight off the palettes rather than
    // block by block: a section knows how many of each state it holds without being walked.
    public static ChunkInfoPayload overview(LevelChunk chunk) {
        Map<Block, Integer> counts = new HashMap<>();
        int[] total = {0};
        for (LevelChunkSection section : chunk.getSections()) {
            if (section.hasOnlyAir()) {
                continue;
            }
            section.getStates().count((state, count) -> {
                if (!state.isAir()) {
                    counts.merge(state.getBlock(), count, Integer::sum);
                    total[0] += count;
                }
            });
        }

        List<Map.Entry<Block, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        List<BlockCount> top = new ArrayList<>();
        for (Map.Entry<Block, Integer> entry : sorted) {
            if (top.size() >= OVERVIEW_ENTRIES) {
                break;
            }
            top.add(new BlockCount(String.valueOf(BuiltInRegistries.BLOCK.getKey(entry.getKey())), entry.getValue()));
        }
        return new ChunkInfoPayload(chunk.getPos().x(), chunk.getPos().z(), total[0], top);
    }
}
