package com.magmaguy.magmacore.util;

import org.bukkit.Chunk;
import org.bukkit.Location;

public class ChunkLocationChecker {
    private ChunkLocationChecker() {
    }

    public static boolean chunkAtLocationIsLoaded(Location location) {
        return location != null && location.getWorld() != null &&
                location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    /**
     * Converts a chunk to a string key in format "worldName:chunkX:chunkZ"
     *
     * @param chunk The chunk to convert
     * @return String key representing the chunk
     */
    public static String chunkToString(Chunk chunk) {
        return chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ();
    }
}