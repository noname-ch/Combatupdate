package ch.bbcag.gipfeliarmy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

import ch.bbcag.gipfeliarmy.territory.TerritoryClaim;

// Every guard post each player has set down, in the order they set them down - which is what
// gives a post its number. "Post 1" is the first one you placed, and stays Post 1 for as long as
// it stands; when it is broken the ones after it move up.
//
// Saved with the world rather than found by looking, because a post three thousand blocks away in
// an unloaded chunk is still your Post 2, and no amount of scanning loaded block entities would
// turn it up.
public final class GipfaeliArmyData extends SavedData {
    public record PostRef(String dimension, BlockPos pos) {
        public static final Codec<PostRef> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("dimension").forGetter(PostRef::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(PostRef::pos))
                .apply(instance, PostRef::new));
    }

    private record Owner(UUID owner, List<PostRef> posts) {
        static final Codec<Owner> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Owner::owner),
                PostRef.CODEC.listOf().fieldOf("posts").forGetter(Owner::posts))
                .apply(instance, Owner::new));
    }

    private static final Codec<GipfaeliArmyData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Owner.CODEC.listOf().optionalFieldOf("owners", List.of()).forGetter(GipfaeliArmyData::owners))
            .apply(instance, GipfaeliArmyData::new));

    public static final SavedDataType<GipfaeliArmyData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(GipfeliArmyMod.MODID, "army"), GipfaeliArmyData::new, CODEC);

    private final Map<UUID, List<PostRef>> posts = new HashMap<>();

    public GipfaeliArmyData() {
    }

    private GipfaeliArmyData(List<Owner> owners) {
        for (Owner owner : owners) {
            this.posts.put(owner.owner(), new ArrayList<>(owner.posts()));
        }
    }

    private List<Owner> owners() {
        List<Owner> owners = new ArrayList<>();
        for (Map.Entry<UUID, List<PostRef>> entry : this.posts.entrySet()) {
            owners.add(new Owner(entry.getKey(), List.copyOf(entry.getValue())));
        }

        return owners;
    }

    // The same file under the name the combat mod saved it as before the split; see get.
    private static final SavedDataType<GipfaeliArmyData> LEGACY_TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(GipfeliArmyMod.LEGACY_NAMESPACE, "army"), GipfaeliArmyData::new, CODEC);

    public static GipfaeliArmyData get(MinecraftServer server) {
        SavedDataStorage storage = server.getDataStorage();
        GipfaeliArmyData data = storage.get(TYPE);
        if (data == null) {
            // A world from before the split keeps its guard posts under the old name. They are read
            // from there once and saved under the new one from then on; the old file is left as it
            // was. Nothing in here is named after a mod, so the same object serves under both.
            GipfaeliArmyData legacy = storage.get(LEGACY_TYPE);
            if (legacy != null) {
                GipfeliArmyMod.LOGGER.info("Read {} commanders' guard posts from the combat mod's old save file", legacy.posts.size());
            }
            data = legacy == null ? new GipfaeliArmyData() : legacy;
            storage.set(TYPE, data);
            data.setDirty();
        }
        return data;
    }

    public void addPost(UUID owner, ServerLevel level, BlockPos pos) {
        PostRef ref = new PostRef(TerritoryClaim.Key.dimensionOf(level), pos.immutable());
        List<PostRef> mine = this.posts.computeIfAbsent(owner, id -> new ArrayList<>());
        if (!mine.contains(ref)) {
            mine.add(ref);
            this.setDirty();
        }
    }

    // A player's posts in order, with any that have since been broken dropped on the way past. A
    // post in a chunk nobody has loaded is given the benefit of the doubt: it was there last time
    // anyone looked.
    public List<PostRef> posts(MinecraftServer server, UUID owner) {
        List<PostRef> mine = this.posts.get(owner);
        if (mine == null) {
            return List.of();
        }

        boolean changed = mine.removeIf(ref -> {
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(ref.dimension())));
            if (level == null) {
                return true;
            }

            return level.isLoaded(ref.pos()) && !(level.getBlockEntity(ref.pos()) instanceof GipfaeliGuardPost.Post);
        });
        if (changed) {
            this.setDirty();
        }

        return List.copyOf(mine);
    }

    // Which number a post is to its owner, or 0 for one that is not on the list.
    public int number(MinecraftServer server, UUID owner, Level level, BlockPos pos) {
        List<PostRef> mine = this.posts(server, owner);
        PostRef ref = new PostRef(TerritoryClaim.Key.dimensionOf(level), pos.immutable());
        return mine.indexOf(ref) + 1;
    }
}
