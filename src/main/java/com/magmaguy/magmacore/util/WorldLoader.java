package com.magmaguy.magmacore.util;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;
import java.util.logging.Filter;

public class WorldLoader {
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
        File folder = new File(Bukkit.getWorldContainer().getAbsolutePath());
        if (!Files.exists(Paths.get(folder.getAbsolutePath() + File.separatorChar + worldName))) {
            Logger.warn("File  " + folder.getAbsolutePath() + File.separatorChar + worldName + " does not exist!");
            return null;
        }
        Logger.info("Loading world " + worldName + " !");

        Filter filter = newFilter -> false;
        Filter previousFilter = Bukkit.getLogger().getFilter();
        Bukkit.getLogger().setFilter(filter);

        try {
            WorldCreator worldCreator = new WorldCreator(worldName);
            worldCreator.environment(environment);
            worldCreator.keepSpawnInMemory(false);
            worldCreator.generator(new VoidGenerator());
            World world = Bukkit.createWorld(worldCreator);
            if (world != null) world.setKeepSpawnInMemory(false);
            world.setDifficulty(Difficulty.HARD);
            world.setAutoSave(false);
            Bukkit.getLogger().setFilter(previousFilter);
            return world;
        } catch (Exception exception) {
            Bukkit.getLogger().setFilter(previousFilter);
            Logger.warn("Failed to load world " + worldName + " !");
            exception.printStackTrace();
        }
        return null;
    }

    private static class VoidGenerator extends ChunkGenerator {

        @Override
        public void generateSurface(WorldInfo info, Random random, int x, int z, ChunkData data) {}

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
