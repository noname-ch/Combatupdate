package ch.bbcag.gipfeliarmy;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.toml.TomlFormat;

import net.neoforged.fml.loading.FMLPaths;

// Before the split every setting of this mod lived in the combat mod's config file, in tables of
// the same names. The first time this mod starts on a machine with no config file of its own, those
// tables are copied out of the old file, so nothing that was tuned there is lost.
//
// The combat mod strips the tables it no longer knows the moment its own config loads, and keeps a
// numbered .bak of what the file was before it did; so if the live file has already lost them, the
// backups are read instead, newest first. Whatever is written here is then loaded and corrected by
// NeoForge like any other config file, which fills in every value not copied and its comments.
final class LegacyConfig {
    private static final String OLD_STEM = GipfeliArmyMod.LEGACY_NAMESPACE + "-common";
    private static final String NEW_FILE = GipfeliArmyMod.MODID + "-common.toml";
    private static final Pattern BACKUP = Pattern.compile(Pattern.quote(OLD_STEM) + "-\\d+\\.toml\\.bak");

    // The tables that moved, and the switches on the Features page that moved with them.
    private static final List<String> TABLES = List.of("gipfaeli", "gipfaeliExplosives", "gipfaeliArmy", "territory");
    private static final List<String> FEATURES = List.of("enableGipfaeli", "enableGipfaeliBomb", "enableGipfaeliExplosives", "enableTerritory");

    private LegacyConfig() {
    }

    static void migrate() {
        Path dir = FMLPaths.CONFIGDIR.get();
        Path target = dir.resolve(NEW_FILE);
        if (Files.exists(target)) {
            return;
        }

        try {
            for (Path source : candidates(dir)) {
                CommentedConfig old;
                try (Reader reader = Files.newBufferedReader(source)) {
                    old = TomlFormat.instance().createParser().parse(reader);
                }
                // Only a file from before the split has this table; a newer one has nothing to give.
                if (!old.contains("gipfaeliArmy")) {
                    continue;
                }

                CommentedConfig fresh = TomlFormat.newConfig();
                for (String feature : FEATURES) {
                    Object value = old.get("features." + feature);
                    if (value != null) {
                        fresh.set("features." + feature, value);
                    }
                }
                for (String table : TABLES) {
                    if (old.get(table) instanceof Config moved) {
                        fresh.set(table, moved);
                    }
                }
                TomlFormat.instance().createWriter().write(fresh, target, WritingMode.REPLACE);
                GipfeliArmyMod.LOGGER.info("Carried the Gipfaeli army, arsenal and territory settings over from {} into {}",
                        source.getFileName(), target.getFileName());
                return;
            }
        } catch (IOException | RuntimeException e) {
            // Defaults are a fine place to start from; losing a tuned value is not worth a crash.
            GipfeliArmyMod.LOGGER.warn("Could not carry settings over from the combat mod's config; starting from defaults", e);
        }
    }

    // The old file as it is now, then its backups from newest to oldest.
    private static List<Path> candidates(Path dir) throws IOException {
        List<Path> found = new ArrayList<>();
        Path live = dir.resolve(OLD_STEM + ".toml");
        if (Files.isRegularFile(live)) {
            found.add(live);
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(path -> BACKUP.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingLong(LegacyConfig::modified).reversed())
                    .forEach(found::add);
        }
        return found;
    }

    // By time rather than by the number in the name: NeoForge shifts the numbers along as it rotates.
    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
