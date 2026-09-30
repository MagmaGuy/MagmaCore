package com.magmaguy.magmacore.match;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.WorldFolderResolver;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Marks temporary match worlds with their owning plugin so a crash cannot strand them: the
 * next startup deletes unloaded marked folders that belong to the same plugin.
 */
final class LeftoverWorlds {
    static final String MARKER_FILE = ".magmacore-match-world";
    static final String MARKER_VERSION = "magmacore-match-world-v1";

    private LeftoverWorlds() {
    }

    static void writeMarker(Path folder, String pluginName, String worldName) throws IOException {
        Files.writeString(folder.resolve(MARKER_FILE),
                MARKER_VERSION + "\n" + pluginName + "\n" + worldName + "\n", StandardCharsets.UTF_8);
    }

    /** Best effort. Some worlds, including MockBukkit's, have no folder on disk. */
    static void markLoaded(World world) {
        File folder;
        try {
            folder = world.getWorldFolder();
        } catch (RuntimeException unsupported) {
            try {
                folder = WorldFolderResolver.resolve(world.getName());
            } catch (RuntimeException unresolvable) {
                return;
            }
        }
        if (folder == null || !folder.isDirectory()) return;
        try {
            writeMarker(folder.toPath(), MatchCore.plugin().getName(), world.getName());
        } catch (IOException failure) {
            Logger.warn("Could not mark temporary world " + world.getName() + ": " + failure.getMessage());
        }
    }

    static void sweep(String pluginName, Iterable<String> worldNames, Function<String, Path> folderOf,
                      Predicate<String> loaded, Consumer<String> delete) {
        for (String name : worldNames) {
            if (loaded.test(name)) continue;
            Path folder = folderOf.apply(name);
            if (folder == null) continue;
            Path marker = folder.resolve(MARKER_FILE);
            if (!Files.isRegularFile(marker)) continue;
            try {
                List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
                if (lines.size() >= 2 && MARKER_VERSION.equals(lines.get(0)) && pluginName.equals(lines.get(1))) {
                    Logger.info("Deleting leftover match world " + name);
                    delete.accept(name);
                }
            } catch (IOException failure) {
                Logger.warn("Could not read world marker " + marker + ": " + failure.getMessage());
            }
        }
    }
}
