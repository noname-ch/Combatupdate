package ch.bbcag.gipfeliarmy.territory;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

// One claimed chunk: where it is, whose it is, and since when.
//
// A claim is a whole chunk rather than a hand-drawn region, because a chunk is the one unit of
// ground the game already tracks, draws on a map and loads as a piece; it needs no corners, no
// overlap check and no "is this block inside" arithmetic beyond a shift by four.
public record TerritoryClaim(String dimension, int chunkX, int chunkZ, UUID owner, String ownerName, long claimedAt) {
    public static final Codec<TerritoryClaim> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("dimension").forGetter(TerritoryClaim::dimension),
            Codec.INT.fieldOf("x").forGetter(TerritoryClaim::chunkX),
            Codec.INT.fieldOf("z").forGetter(TerritoryClaim::chunkZ),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(TerritoryClaim::owner),
            Codec.STRING.fieldOf("ownerName").forGetter(TerritoryClaim::ownerName),
            Codec.LONG.fieldOf("claimedAt").forGetter(TerritoryClaim::claimedAt))
            .apply(instance, TerritoryClaim::new));

    public Key key() {
        return new Key(dimension, chunkX, chunkZ);
    }

    public boolean ownedBy(UUID player) {
        return owner.equals(player);
    }

    // What a claim is looked up by. The dimension travels as its id string so the key can be
    // built on either side of the connection without a registry in hand.
    public record Key(String dimension, int chunkX, int chunkZ) {
        public static Key of(Level level, ChunkPos pos) {
            return new Key(dimensionOf(level), pos.x(), pos.z());
        }

        public static Key of(Level level, int chunkX, int chunkZ) {
            return new Key(dimensionOf(level), chunkX, chunkZ);
        }

        public static String dimensionOf(Level level) {
            return level.dimension().identifier().toString();
        }
    }
}
