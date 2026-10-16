package com.magmaguy.magmacore.util;

import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

/**
 * Leaves every chunk empty.
 *
 * <p>{@link TemporaryWorldManager#voidGenerator()} loads a copy of this class through
 * {@link DetachedClassLoading}, so it may only reference JDK and Bukkit types.</p>
 */
public final class VoidWorldGenerator extends ChunkGenerator {

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
