package com.magmaguy.magmacore.nightbreak;

import org.bukkit.Bukkit;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;

/**
 * Swaps new local builds into a running test server. Off unless the server JVM sets
 * {@code -Dnightbreak.devUpdateSource=<folder>}; the testbed start scripts point it at the
 * workspace {@code dist/} folder, where every build mirrors its jar.
 *
 * <p>Each plugin watches the file in that folder named like its own jar. A finished build whose
 * bytes differ from the running jar is staged in the update folder and swapped in at once through
 * {@link NightbreakPluginHotSwap}, even when it keeps the same version. A build that is refused or
 * fails to enable is not tried again; the next build is.</p>
 */
public final class NightbreakDevUpdateSource {
    public static final String SOURCE_PROPERTY = "nightbreak.devUpdateSource";
    // System properties survive the plugin reload a swap performs; this class's statics do not.
    private static final String ATTEMPT_PREFIX = "nightbreak.devUpdateSource.attempt.";
    private static final String SKIPPED_PREFIX = "nightbreak.devUpdateSource.skipped.";
    // A build a restart-only plugin can only take at the next boot; test harnesses restart for it.
    private static final String NEEDS_RESTART_PREFIX = "nightbreak.devUpdateSource.needsRestart.";
    private static final long POLL_TICKS = 40L;

    private NightbreakDevUpdateSource() {
    }

    static File sourceFolder() {
        String folder = System.getProperty(SOURCE_PROPERTY);
        return folder == null || folder.isBlank() ? null : new File(folder.trim());
    }

    /**
     * Reports how the last swap of this plugin ended and starts watching for its next build.
     *
     * @return true when the dev source is active, so Nightbreak update downloads stay off
     */
    static boolean startIfConfigured(JavaPlugin plugin) {
        File folder = sourceFolder();
        if (folder == null) return false;
        File runningJar = NightbreakPluginHotSwap.findPluginJar(plugin);
        if (runningJar == null) {
            plugin.getLogger().warning("Dev update source: could not find the jar " + plugin.getName() + " runs from.");
            return true;
        }
        String runningHash = NightbreakPluginUpdater.sha256(runningJar);
        reportFinishedSwap(plugin, runningJar, runningHash);
        Watcher watcher = new Watcher(plugin, runningHash, new BuildWatch(new File(folder, runningJar.getName())));
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, watcher::poll, POLL_TICKS, POLL_TICKS);
        plugin.getLogger().info("Dev update source: watching " + watcher.build.source() + " for new builds.");
        return true;
    }

    private static void reportFinishedSwap(JavaPlugin plugin, File runningJar, String runningHash) {
        String attempt = System.getProperty(ATTEMPT_PREFIX + plugin.getName());
        if (attempt == null) return;
        System.clearProperty(ATTEMPT_PREFIX + plugin.getName());
        String[] parts = attempt.split("\\|");
        if (parts.length != 3) return;
        long millis = System.currentTimeMillis() - Long.parseLong(parts[2]);
        if (parts[1].equals(runningHash)) {
            plugin.getLogger().info("Dev update source: hot-swapped " + shortHash(parts[0]) + " -> "
                    + shortHash(parts[1]) + " in " + millis + " ms.");
            return;
        }
        System.setProperty(SKIPPED_PREFIX + plugin.getName(), parts[1]);
        plugin.getLogger().warning("Dev update source: build " + shortHash(parts[1]) + " did not enable, so "
                + shortHash(runningHash) + " was loaded again. The next build will be tried.");
        File staged = new File(NightbreakPluginUpdater.updateFolder(plugin), runningJar.getName());
        if (staged.isFile() && parts[1].equals(NightbreakPluginUpdater.sha256(staged))) staged.delete();
    }

    static String shortHash(String hash) {
        return hash.length() > 10 ? hash.substring(0, 10) : hash;
    }

    private static final class Watcher {
        private final JavaPlugin plugin;
        private final String runningHash;
        private final BuildWatch build;

        private Watcher(JavaPlugin plugin, String runningHash, BuildWatch build) {
            this.plugin = plugin;
            this.runningHash = runningHash;
            this.build = build;
        }

        private void poll() {
            if (!plugin.isEnabled()) return;
            try {
                String hash = build.poll(runningHash, System.getProperty(SKIPPED_PREFIX + plugin.getName()));
                if (hash == null) return;
                String refusal = describeRefusal(build.source());
                if (refusal != null) {
                    skip(hash, refusal);
                    return;
                }
                if (!NightbreakPluginHotSwap.offersRestartFreeUpdates(plugin)) {
                    System.setProperty(SKIPPED_PREFIX + plugin.getName(), hash);
                    System.setProperty(NEEDS_RESTART_PREFIX + plugin.getName(), hash);
                    plugin.getLogger().info("Dev update source: build " + shortHash(hash) + " waits for a restart; "
                            + plugin.getName() + " only updates when the server starts.");
                    return;
                }
                File staged = stage(hash);
                if (staged == null) {
                    build.retry(); // The build changed while it was copied.
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> apply(hash, staged));
            } catch (IOException | RuntimeException exception) {
                build.retry();
            }
        }

        private String describeRefusal(File source) {
            try (JarFile jar = new JarFile(source)) {
                var entry = jar.getJarEntry("plugin.yml");
                if (entry == null) return "it has no plugin.yml";
                try (InputStream input = jar.getInputStream(entry)) {
                    String name = new PluginDescriptionFile(input).getName();
                    return name.equals(plugin.getName()) ? null : "it is " + name + ", not " + plugin.getName();
                }
            } catch (IOException | InvalidDescriptionException exception) {
                return "it is not a readable plugin jar";
            }
        }

        private File stage(String hash) throws IOException {
            File updateFolder = NightbreakPluginUpdater.updateFolder(plugin);
            Files.createDirectories(updateFolder.toPath());
            File staged = new File(updateFolder, build.source().getName());
            File temporary = new File(updateFolder, "." + staged.getName() + ".dev-" + hash.substring(0, 8));
            Files.copy(build.source().toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (!hash.equals(NightbreakPluginUpdater.sha256(temporary))) {
                Files.deleteIfExists(temporary.toPath());
                return null;
            }
            Files.move(temporary.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return staged;
        }

        private void apply(String hash, File staged) {
            if (!plugin.isEnabled()) return;
            if (System.getProperty(PluginHotSwapRoutine.ACTIVE_PROPERTY) != null) {
                build.retry(); // Another plugin is swapping; try on the next poll.
                return;
            }
            System.setProperty(ATTEMPT_PREFIX + plugin.getName(), runningHash + "|" + hash + "|" + System.currentTimeMillis());
            plugin.getLogger().info("Dev update source: new build " + shortHash(hash) + " found, swapping it in.");
            if (NightbreakPluginHotSwap.applyStagedUpdate(plugin, Bukkit.getConsoleSender(), true)) return;
            System.clearProperty(ATTEMPT_PREFIX + plugin.getName());
            staged.delete();
            skip(hash, "the swap was refused (reason above)");
        }

        private void skip(String hash, String reason) {
            System.setProperty(SKIPPED_PREFIX + plugin.getName(), hash);
            plugin.getLogger().warning("Dev update source: not swapping build " + shortHash(hash) + " because "
                    + reason + ". The next build will be tried.");
        }
    }

    /** The poll decisions, free of Bukkit so they can be tested with plain files. */
    static final class BuildWatch {
        private final File source;
        private long size = -1L;
        private long modified = -1L;
        private volatile boolean decided;

        BuildWatch(File source) {
            this.source = source;
        }

        File source() {
            return source;
        }

        /**
         * @return the hash of a finished build that differs from both the running and the skipped
         * hash, at most once per build; null while the file is missing, still changing or decided
         */
        String poll(String runningHash, String skippedHash) {
            if (!source.isFile()) {
                size = -1L;
                modified = -1L;
                decided = false;
                return null;
            }
            long currentSize = source.length();
            long currentModified = source.lastModified();
            if (currentSize != size || currentModified != modified) {
                size = currentSize;
                modified = currentModified;
                decided = false;
                return null; // A build may still be writing it; look again next poll.
            }
            if (decided) return null;
            decided = true;
            String hash = NightbreakPluginUpdater.sha256(source);
            return hash.equals(runningHash) || hash.equals(skippedHash) ? null : hash;
        }

        /** Decides the current file again on the next poll. */
        void retry() {
            decided = false;
        }
    }
}
