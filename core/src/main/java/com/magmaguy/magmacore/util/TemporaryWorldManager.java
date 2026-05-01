package com.magmaguy.magmacore.util;

import com.magmaguy.magmacore.MagmaCore;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Random;

public class TemporaryWorldManager {
    /**
     * Generates a world which does not save, does not keep spawn loaded and is a void world
     *
     * @param worldName
     * @param environment
     * @return
     */
    public static World loadVoidTemporaryWorld(String worldName, World.Environment environment) {
        //Case where the world is already loaded
        if (Bukkit.getWorld(worldName) != null) return Bukkit.getWorld(worldName);
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
            worldCreator.generator(new VoidGenerator());
            World world = Bukkit.createWorld(worldCreator);
            if (world != null) world.setKeepSpawnInMemory(false);
            world.setDifficulty(Difficulty.HARD);
//            world.setAutoSave(false);
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
        if (Bukkit.getWorld(worldName) != null) return Bukkit.getWorld(worldName);
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
            if (world != null) world.setKeepSpawnInMemory(false);
            if (world != null) world.setDifficulty(Difficulty.HARD);
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
        if (Bukkit.getWorld(worldName) != null) return Bukkit.getWorld(worldName);
        WorldFolderResolver.quarantineMigrationDebris(worldName);

        try {
            Logger.info("Creating world " + worldName + " with custom generator !");
            WorldCreator worldCreator = new WorldCreator(worldName);
            worldCreator.environment(environment);
            worldCreator.generator(generator);
            World world = Bukkit.createWorld(worldCreator);
            if (world != null) {
                world.setKeepSpawnInMemory(false);
                world.setDifficulty(Difficulty.HARD);
                world.setAutoSave(false);
            }
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
            worldCreator.generator(new VoidGenerator());
            World world = Bukkit.createWorld(worldCreator);
            world.setKeepSpawnInMemory(false);
            world.setDifficulty(Difficulty.HARD);
            world.setAutoSave(false);
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
        world.setAutoSave(false);
        if (!Bukkit.unloadWorld(world, false)) {
            Logger.warn("Failed to unload world " + world.getName() + " ! This is bad, report this to the developer!");
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                deleteWorldDirectory(world.getName());
            }
        }.runTaskAsynchronously(MagmaCore.getInstance().getRequestingPlugin());
    }

    public static void permanentlyDeleteWorld(World world) {
        if (MagmaCore.getInstance().getRequestingPlugin().isEnabled())
            asyncPermanentlyDeleteWorld(world);
        else
            syncPermanentlyDeleteWorld(world);
    }


    /**
     * This is not ideal, but it is necessary if you need to delete a world on a shutdown sequence since you can't register
     * async tasks while shutting down, nor would it be desirable
     *
     * @param world
     */
    public static void syncPermanentlyDeleteWorld(World world) {
        world.setAutoSave(false);
        if (!Bukkit.unloadWorld(world, false)) {
            Logger.warn("Failed to unload world " + world.getName() + " ! This is bad, report this to the developer!");
        }
        deleteWorldDirectory(world.getName());
    }

    private static void deleteWorldDirectory(String worldName) {
        WorldFolderResolver.deleteAllLayouts(worldName);
        Logger.info("Successfully deleted temporary world " + worldName);
    }

    private static class VoidGenerator extends ChunkGenerator {

        @Override
        public void generateSurface(WorldInfo info, Random random, int x, int z, ChunkData data) {
        }

        @Override
        public boolean shouldGenerateNoise() {
            return false;
        }

        @Override
        public boolean shouldGenerateBedrock() {
            return false;
        }

        @Override
        public boolean shouldGenerateCaves() {
            return false;
        }
    }
}
