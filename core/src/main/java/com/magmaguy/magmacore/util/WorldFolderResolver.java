package com.magmaguy.magmacore.util;

import org.apache.commons.io.FileUtils;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Resolves legacy world roots and modern per-dimension storage. */
public final class WorldFolderResolver {

    private static final String DIMENSIONS_DIRECTORY = "dimensions";
    private static final String DEFAULT_NAMESPACE = "minecraft";
    private static final String LEVEL_DAT = "level.dat";
    private static final String LEVEL_OLD_DAT = "level.dat_old";

    private WorldFolderResolver() {
    }

    /**
     * Always returns the legacy path: {@code <worldContainer>/<name>/}, regardless
     * of whether anything exists there.
     */
    public static Path legacyFolder(String worldName) {
        return Bukkit.getWorldContainer().toPath().resolve(worldName);
    }

    /**
     * Always returns the Paper 26.1+ path:
     * {@code <worldContainer>/<level-name>/dimensions/minecraft/<name>/}, even when
     * the dimensions root doesn't exist on disk.
     */
    public static Path modernFolder(String worldName) {
        // Paper derives the dimension key from the lowercase world name, even
        // when the legacy folder and Bukkit world name contain capital letters.
        return modernNamespaceRoot().resolve(worldName.toLowerCase(Locale.ROOT));
    }

    /** Resolves the dimension namespace beside the loaded primary dimension, including custom level-name roots. */
    public static Path modernNamespaceRoot() {
        if (usesModernStorage()) {
            return Bukkit.getWorlds().getFirst().getWorldFolder().toPath().getParent().getParent()
                    .resolve(DEFAULT_NAMESPACE);
        }
        String levelName = Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().getFirst().getName();
        return Bukkit.getWorldContainer().toPath().resolve(levelName).resolve(DIMENSIONS_DIRECTORY).resolve(DEFAULT_NAMESPACE);
    }

    /** The loaded primary world's actual directory establishes the server's storage layout. */
    public static boolean usesModernStorage() {
        if (Bukkit.getWorlds().isEmpty()) return false;
        File folder = Bukkit.getWorlds().getFirst().getWorldFolder();
        if (folder == null) return false;
        Path parent = folder.toPath().toAbsolutePath().getParent();
        return parent != null && parent.getParent() != null
                && DIMENSIONS_DIRECTORY.equals(parent.getParent().getFileName().toString());
    }
    /**
     * True iff Paper has migrated this world to the vanilla-style layout
     * (Paper metadata and per-world overrides exist at the dimension path).
     */
    public static boolean hasModernLayout(String worldName) {
        Path folder = modernFolder(worldName);
        Path paperData = folder.resolve("data").resolve("paper");
        return Files.isRegularFile(paperData.resolve("metadata.dat"))
                && Files.isRegularFile(paperData.resolve("level_overrides.dat"));
    }

    /**
     * True iff a {@code level.dat} is present at the legacy path.
     */
    public static boolean hasLegacyLayout(String worldName) {
        return Files.isRegularFile(legacyFolder(worldName).resolve(LEVEL_DAT));
    }

    /**
     * Returns the live data folder for a world, preferring the modern path when
     * both layouts have world metadata. Returns the legacy path when neither
     * has data so callers writing fresh world folders keep their existing
     * destination on Spigot / pre-26.1 Paper / first-time creation.
     */
    public static File resolve(String worldName) {
        if (hasModernLayout(worldName)) return modernFolder(worldName).toFile();
        return legacyFolder(worldName).toFile();
    }

    /**
     * True iff the world has recognized metadata at either layout.
     */
    public static boolean exists(String worldName) {
        return hasModernLayout(worldName) || hasLegacyLayout(worldName);
    }

    /**
     * True iff a directory exists at either layout (with or without
     * {@code level.dat}). Useful for scanning for partial / blueprint folders or
     * picking a unique new-world name.
     */
    public static boolean folderExists(String worldName) {
        return Files.isDirectory(modernFolder(worldName)) || Files.isDirectory(legacyFolder(worldName));
    }

    /**
     * Lists every world-folder name visible at the legacy server root OR under
     * the modern dimensions/minecraft directory. Insertion order is legacy
     * first, then modern, deduplicated.
     */
    public static Set<String> listAllWorldNames() {
        Set<String> names = new LinkedHashSet<>();
        File legacyRoot = Bukkit.getWorldContainer();
        File[] legacyChildren = legacyRoot.listFiles(File::isDirectory);
        if (legacyChildren != null) {
            for (File child : legacyChildren) names.add(child.getName());
        }
        File modernRoot = modernNamespaceRoot().toFile();
        File[] modernChildren = modernRoot.listFiles(File::isDirectory);
        if (modernChildren != null) {
            for (File child : modernChildren) names.add(child.getName());
        }
        return names;
    }

    /**
     * Detects Paper migration debris and renames the stale legacy folder aside
     * so the next {@link Bukkit#createWorld} call does not trigger
     * {@code WorldMigrationSupport.mergeMove}. Quarantine name is
     * {@code <name>.paper-migration-backup-<epoch-millis>}.
     *
     * <p>Triggers when Paper would attempt a legacy migration (legacy folder has
     * {@code level.dat} or {@code level.dat_old}) AND the modern target folder
     * already has any content (which is what causes {@code mergeMove} to throw).
     * That covers both the "successfully migrated once but legacy folder still
     * present" case AND the "previous migration failed midway, modern has region
     * files but no level.dat yet" case.
     *
     * <p>No-op on Spigot, pre-26.1 Paper, fresh installs, and any state where
     * only one of the two layouts holds data.
     *
     * @return {@code true} if a quarantine actually happened
     */
    public static boolean quarantineMigrationDebris(String worldName) {
        if (!legacyTriggersPaperMigration(worldName)) return false;
        if (!modernHasAnyContent(worldName)) return false;
        Path legacy = legacyFolder(worldName);
        Path quarantine = legacy.resolveSibling(worldName + ".paper-migration-backup-" + System.currentTimeMillis());
        try {
            Files.move(legacy, quarantine);
            Logger.warn("Quarantined stale legacy world folder " + legacy + " -> " + quarantine
                    + " (Paper migration debris). Inspect and delete after verifying the world loads.");
            return true;
        } catch (IOException e) {
            Logger.warn("Failed to quarantine legacy world folder " + legacy + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Mirror of Paper's own {@code LegacyCraftBukkitWorldMigration.migrateApiWorld}
     * entry condition: a legacy migration is triggered iff the legacy folder
     * contains {@code level.dat} or {@code level.dat_old}.
     */
    private static boolean legacyTriggersPaperMigration(String worldName) {
        Path legacy = legacyFolder(worldName);
        return Files.isRegularFile(legacy.resolve(LEVEL_DAT))
                || Files.isRegularFile(legacy.resolve(LEVEL_OLD_DAT));
    }

    /**
     * True iff the modern folder exists and contains any file or sub-directory.
     * A partial Paper migration leaves region files in the modern folder even
     * before {@code level.dat} is moved across, so this is the right test for
     * "the destination would conflict with a fresh migration attempt."
     */
    private static boolean modernHasAnyContent(String worldName) {
        Path modern = modernFolder(worldName);
        if (!Files.isDirectory(modern)) return false;
        try (java.util.stream.Stream<Path> children = Files.list(modern)) {
            return children.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Deletes the world's data folder at both legacy and modern layouts. Errors
     * on either are logged and swallowed so a missing/stale path on one layout
     * doesn't prevent cleanup on the other.
     */
    public static void deleteAllLayouts(String worldName) {
        File legacy = legacyFolder(worldName).toFile();
        File modern = modernFolder(worldName).toFile();
        if (legacy.isDirectory()) {
            try {
                FileUtils.deleteDirectory(legacy);
            } catch (IOException e) {
                Logger.warn("Failed to delete legacy world folder " + legacy + ": " + e.getMessage());
            }
        }
        if (modern.isDirectory()) {
            try {
                FileUtils.deleteDirectory(modern);
            } catch (IOException e) {
                Logger.warn("Failed to delete modern world folder " + modern + ": " + e.getMessage());
            }
        }
    }
}
