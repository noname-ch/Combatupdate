package ch.bbcag.gipfeliarmy.territory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

import ch.bbcag.gipfeliarmy.GipfeliArmyMod;

// Every claim on the server, and every message waiting for a player who was offline when it was
// sent. Saved with the world (data/gipfeliarmy_territory.dat in the overworld's folder), so
// territory survives a restart the same way the world it sits on does.
//
// One file for all dimensions rather than one per level: a player's claim count spans dimensions,
// and the notices are about players rather than places.
public final class TerritoryData extends SavedData {
    // A message for a player who was not there to receive it, kept as a translation key and its
    // arguments so it can be rendered in the reader's own language when they do turn up.
    public record Notice(UUID target, String key, List<String> args, long time) {
        public static final Codec<Notice> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("target").forGetter(Notice::target),
                Codec.STRING.fieldOf("key").forGetter(Notice::key),
                Codec.STRING.listOf().fieldOf("args").forGetter(Notice::args),
                Codec.LONG.fieldOf("time").forGetter(Notice::time))
                .apply(instance, Notice::new));
    }

    private static final Codec<TerritoryData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            TerritoryClaim.CODEC.listOf().optionalFieldOf("claims", List.of())
                    .forGetter(data -> List.copyOf(data.claims.values())),
            Notice.CODEC.listOf().optionalFieldOf("notices", List.of())
                    .forGetter(data -> List.copyOf(data.notices)))
            .apply(instance, TerritoryData::new));

    public static final SavedDataType<TerritoryData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(GipfeliArmyMod.MODID, "territory"), TerritoryData::new, CODEC);

    private final Map<TerritoryClaim.Key, TerritoryClaim> claims = new HashMap<>();
    private final List<Notice> notices = new ArrayList<>();

    public TerritoryData() {
    }

    private TerritoryData(List<TerritoryClaim> claims, List<Notice> notices) {
        for (TerritoryClaim claim : claims) {
            this.claims.put(claim.key(), claim);
        }
        this.notices.addAll(notices);
    }

    // The same file under the name the combat mod saved it as before the split; see get.
    private static final SavedDataType<TerritoryData> LEGACY_TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(GipfeliArmyMod.LEGACY_NAMESPACE, "territory"), TerritoryData::new, CODEC);

    public static TerritoryData get(MinecraftServer server) {
        SavedDataStorage storage = server.getDataStorage();
        TerritoryData data = storage.get(TYPE);
        if (data == null) {
            // A world from before the split keeps its claims under the old name. They are read from
            // there once and saved under the new one from then on; the old file is left as it was.
            TerritoryData legacy = storage.get(LEGACY_TYPE);
            if (legacy != null) {
                GipfeliArmyMod.LOGGER.info("Read {} territory claims and {} pending notices from the combat mod's old save file",
                        legacy.claims.size(), legacy.notices.size());
            }
            data = legacy == null ? new TerritoryData() : legacy.renamed();
            storage.set(TYPE, data);
            data.setDirty();
        }
        return data;
    }

    // A copy of this data as the new mod writes it. A pending notice is kept as a translation key,
    // and those keys changed namespace with the mod, so they are moved over here; a claim holds
    // nothing but places and players and comes across as it is.
    private TerritoryData renamed() {
        String oldPrefix = GipfeliArmyMod.LEGACY_NAMESPACE + ".";
        String newPrefix = GipfeliArmyMod.MODID + ".";
        List<Notice> moved = new ArrayList<>(this.notices.size());
        for (Notice notice : this.notices) {
            String key = notice.key().startsWith(oldPrefix)
                    ? newPrefix + notice.key().substring(oldPrefix.length())
                    : notice.key();
            moved.add(new Notice(notice.target(), key, notice.args(), notice.time()));
        }
        return new TerritoryData(List.copyOf(this.claims.values()), moved);
    }

    public @Nullable TerritoryClaim get(TerritoryClaim.Key key) {
        return claims.get(key);
    }

    public void put(TerritoryClaim claim) {
        claims.put(claim.key(), claim);
        setDirty();
    }

    public @Nullable TerritoryClaim remove(TerritoryClaim.Key key) {
        TerritoryClaim removed = claims.remove(key);
        if (removed != null) {
            setDirty();
        }
        return removed;
    }

    public int count(UUID owner) {
        int count = 0;
        for (TerritoryClaim claim : claims.values()) {
            if (claim.ownedBy(owner)) {
                count++;
            }
        }
        return count;
    }

    public List<TerritoryClaim> claimsOf(UUID owner) {
        List<TerritoryClaim> owned = new ArrayList<>();
        for (TerritoryClaim claim : claims.values()) {
            if (claim.ownedBy(owner)) {
                owned.add(claim);
            }
        }
        return owned;
    }

    public Collection<TerritoryClaim> all() {
        return claims.values();
    }

    public void addNotice(Notice notice) {
        notices.add(notice);
        setDirty();
    }

    // Takes every notice addressed to the player out of the queue and hands them over, oldest first.
    public List<Notice> drainNotices(UUID target) {
        List<Notice> drained = new ArrayList<>();
        notices.removeIf(notice -> {
            if (!notice.target().equals(target)) {
                return false;
            }
            drained.add(notice);
            return true;
        });
        if (!drained.isEmpty()) {
            setDirty();
        }
        drained.sort((a, b) -> Long.compare(a.time(), b.time()));
        return drained;
    }
}
