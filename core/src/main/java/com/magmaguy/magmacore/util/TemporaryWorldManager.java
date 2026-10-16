package com.magmaguy.magmacore.util;

import com.magmaguy.magmacore.MagmaCore;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.plugin.Plugin;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public class TemporaryWorldManager {
    private static final long DELETE_AFTER_UNLOAD_DELAY_TICKS = 20L * 5L;
    private static Class<?> voidGeneratorClass;

    /**
     * Generates a world which does not save, does not keep spawn loaded and is a void world
     *
     * @param worldName
     * @param environment
     * @return
     */
    public static World loadVoidTemporaryWorld(String worldName, World.Environment environment) {
        //Case where the world is already loaded
        if (Bukkit.getWorld(worldName) != null) return prepareTemporaryWorld(Bukkit.getWorld(worldName));
        if (!WorldFolderResolver.folderExists(worldName)) {
            Logger.warn("World folder for " + worldName + " does not exist at "
                    + WorldFolderResolver.legacyFolder(worldName) + " or "
                    + WorldFolderResolver.modernFolder(worldName) + "!");
            return null;
        }
        // Quarantine Paper 26.1+ migration debris (no-op on Spigot / clean states)
        // so Bukkit.createWorld doesn't throw "Refusing to overwrite existing migrated file".
        WorldFolderResolver.quarantineMigrationDebris(worldName);
        Logger.info("Loading world " + worldName + " !");

        try {
            Logger.info("Creating world " + worldName + " !");
            WorldCreator worldCreator = new WorldCreator(worldName);
            worldCreator.environment(environment);
            worldCreator.generator(voidGenerator());
            World world = Bukkit.createWorld(worldCreator);
            prepareTemporaryWorld(world);
            Logger.info("Successfully loaded world " + worldName + " !");
//            Bukkit.getLogger().setFilter(previousFilter);
            return world;
        } catch (Exception exception) {
//            Bukkit.getLogger().setFilter(previousFilter);
            Logger.warn("Failed to load world " + worldName + " !");
            exception.printStackTrace();
        }
        return null;
    }

    /**
     * Loads a temporary world using a caller-supplied {@link ChunkGenerator} instead of the
     * built-in void generator. Post-load settings mirror
     * {@link #loadVoidTemporaryWorld(String, World.Environment)}: spawn not kept in memory,
     * difficulty HARD. The world folder is expected to already exist on disk (the caller
     * is responsible for copying a template if needed) — matching the existing void-world
     * contract.
     *
     * @param worldName   unique world folder name under {@link Bukkit#getWorldContainer()}
     * @param environment nether / end / normal
     * @param generator   the {@link ChunkGenerator} responsible for producing surface blocks
     * @return the loaded {@link World}, or {@code null} on failure
     */
    public static World loadTemporaryWorldWithGenerator(String worldName, World.Environment environment, ChunkGenerator generator) {
        //Case where the world is already loaded
        if (Bukkit.getWorld(worldName) != null) return prepareTemporaryWorld(Bukkit.getWorld(worldName));
        if (!WorldFolderResolver.folderExists(worldName)) {
            Logger.warn("World folder for " + worldName + " does not exist at "
                    + WorldFolderResolver.legacyFolder(worldName) + " or "
                    + WorldFolderResolver.modernFolder(worldName) + "!");
            return null;
        }
        WorldFolderResolver.quarantineMigrationDebris(worldName);
        Logger.info("Loading world " + worldName + " with custom generator !");

        try {
            Logger.info("Creating world " + worldName + " with custom generator !");
            WorldCreator worldCreator = new WorldCreator(worldName);
            worldCreator.environment(environment);
            worldCreator.generator(generator);
            World world = Bukkit.createWorld(worldCreator);
            prepareTemporaryWorld(world);
            Logger.info("Successfully loaded world " + worldName + " !");
            return world;
        } catch (Exception exception) {
            Logger.warn("Failed to load world " + worldName + " !");
            exception.printStackTrace();
        }
        return null;
    }

    /**
     * Creates a fresh temporary world using a caller-supplied {@link ChunkGenerator}, with
     * no template folder on disk. Unlike {@link #createVoidTemporaryWorld(String, World.Environment)},
     * this does NOT auto-append an identifier — the caller is responsible for choosing a unique
     * final world name. If a world with {@code worldName} is already loaded, it is returned
     * immediately (matching {@link #loadVoidTemporaryWorld(String, World.Environment)}).
     * <p>
     * Post-create settings mirror {@link #createVoidTemporaryWorld}: spawn not kept in memory,
     * difficulty HARD, autosave off (these worlds are ephemeral).
     *
     * @param worldName   unique world folder name under {@link Bukkit#getWorldContainer()}
     * @param environment nether / end / normal
     * @param generator   the {@link ChunkGenerator} responsible for producing surface blocks
     * @return the created {@link World}, or {@code null} on failure
     */
    public static World createTemporaryWorldWithGenerator(String worldName, World.Environment environment, ChunkGenerator generator) {
        //Case where the world is already loaded
        if (Bukkit.getWorld(worldName) != null) return prepareTemporaryWorld(Bukkit.getWorld(worldName));
        WorldFolderResolver.quarantineMigrationDebris(worldName);

        try {
            Logger.info("Creating world " + worldName + " with custom generator !");
            WorldCreator worldCreator = new WorldCreator(worldName);
            worldCreator.environment(environment);
            worldCreator.generator(generator);
            World world = Bukkit.createWorld(worldCreator);
            prepareTemporaryWorld(world);
            Logger.info("Successfully loaded world " + worldName + " !");
            return world;
        } catch (Exception exception) {
            Logger.warn("Failed to load world " + worldName + " !");
            exception.printStackTrace();
        }
        return null;
    }

    /**
     * Generates a world which does not save, does not keep spawn loaded and is a void world
     *
     * @param worldName
     * @param environment
     * @return
     */
    public static World createVoidTemporaryWorld(String worldName, World.Environment environment) {
        //Case where the world is already loaded
        int identifier = 1;
        while (Bukkit.getWorld(worldName + "_" + identifier) != null
                || WorldFolderResolver.folderExists(worldName + "_" + identifier)) identifier++;
        worldName += "_" + identifier;
        WorldFolderResolver.quarantineMigrationDebris(worldName);

        try {
            Logger.info("Creating world " + worldName + " !");
            WorldCreator worldCreator = new WorldCreator(worldName);
            worldCreator.environment(environment);
            worldCreator.generator(voidGenerator());
            World world = Bukkit.createWorld(worldCreator);
            prepareTemporaryWorld(world);
            Logger.info("Successfully loaded world " + worldName + " !");
//            Bukkit.getLogger().setFilter(previousFilter);
            return world;
        } catch (Exception exception) {
//            Bukkit.getLogger().setFilter(previousFilter);
            Logger.warn("Failed to load world " + worldName + " !");
            exception.printStackTrace();
        }
        return null;
    }

    /**
     * Whenever possible, use this async method over the sync method. This works well during runtime, but will not work on shutdown.
     *
     * @param world
     */
    public static void asyncPermanentlyDeleteWorld(World world) {
        tryAsyncPermanentlyDeleteWorld(world);
    }

    public static boolean tryAsyncPermanentlyDeleteWorld(World world) {
        world.setAutoSave(false);
        String worldName = world.getName();
        if (!Bukkit.unloadWorld(world, false)) {
            Logger.warn("Failed to unload world " + worldName + " ! Skipping folder deletion because the world is still loaded.");
            return false;
        }
        Plugin plugin = MagmaCore.getInstance().getRequestingPlugin();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (Bukkit.getWorld(worldName) != null) {
                    Logger.warn("Skipping folder deletion for " + worldName + " because it was loaded again before cleanup ran.");
                    return;
                }
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        deleteWorldDirectory(worldName);
                    }
                }.runTaskAsynchronously(plugin);
            }
        }.runTaskLater(plugin, DELETE_AFTER_UNLOAD_DELAY_TICKS);
        return true;
    }

    public static void permanentlyDeleteWorld(World world) {
        tryPermanentlyDeleteWorld(world);
    }

    public static boolean tryPermanentlyDeleteWorld(World world) {
        if (MagmaCore.getInstance().getRequestingPlugin().isEnabled())
            return tryAsyncPermanentlyDeleteWorld(world);
        else
            return trySyncPermanentlyDeleteWorld(world);
    }


    /**
     * This is not ideal, but it is necessary if you need to delete a world on a shutdown sequence since you can't register
     * async tasks while shutting down, nor would it be desirable
     *
     * @param world
     */
    public static void syncPermanentlyDeleteWorld(World world) {
        trySyncPermanentlyDeleteWorld(world);
    }

    public static boolean trySyncPermanentlyDeleteWorld(World world) {
        world.setAutoSave(false);
        String worldName = world.getName();
        if (!Bukkit.unloadWorld(world, false)) {
            Logger.warn("Failed to unload world " + worldName + " ! Skipping folder deletion because the world is still loaded.");
            return false;
        }
        deleteWorldDirectory(worldName);
        return true;
    }

    private static void deleteWorldDirectory(String worldName) {
        WorldFolderResolver.deleteAllLayouts(worldName);
        if (WorldFolderResolver.folderExists(worldName))
            Logger.warn("Temporary world " + worldName + " was unloaded, but its folder could not be fully deleted.");
        else
            Logger.info("Successfully deleted temporary world " + worldName);
    }

    private static World prepareTemporaryWorld(World world) {
        if (world == null) return null;
        world.setKeepSpawnInMemory(false);
        world.setDifficulty(Difficulty.HARD);
        world.setAutoSave(false);
        return world;
    }

    /**
     * Returns a generator that leaves every chunk empty. A world keeps its generator for as long
     * as it stays loaded, which outlasts a restart-free plugin update, so the generator's class
     * belongs to its own small loader: one from this plugin's loader would keep the replaced
     * copy of the whole plugin in memory until the server restarts.
     */
    public static ChunkGenerator voidGenerator() {
        try {
            return (ChunkGenerator) voidGeneratorClass().getConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return new VoidWorldGenerator();
        }
    }

    private static synchronized Class<?> voidGeneratorClass() {
        if (voidGeneratorClass != null) return voidGeneratorClass;
        Path directory = null;
        try {
            directory = Files.createTempDirectory("nightbreak-void-generator");
            Class<?> detached = DetachedClassLoading.define(VoidWorldGenerator.class, directory);
            // The class is defined and only uses Bukkit and JDK types, so nothing else loads from here.
            if (detached.getClassLoader() instanceof Closeable closeable) closeable.close();
            voidGeneratorClass = detached;
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError exception) {
            Logger.warn("Could not load the void world generator outside the plugin, so void worlds will keep this "
                    + "copy of the plugin in memory after a restart-free update: " + exception);
            voidGeneratorClass = VoidWorldGenerator.class;
        } finally {
            if (directory != null) deleteQuietly(directory);
        }
        return voidGeneratorClass;
    }

    private static void deleteQuietly(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException ignored) {
            // A leftover temporary directory is harmless.
        }
    }
}
