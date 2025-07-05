package com.magmaguy.magmacore.util;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Bukkit;

public class ChunkLocationChecker {
    private ChunkLocationChecker() {
    }

    public static boolean locationIsInChunk(Location location, Chunk chunk) {
        if (!chunk.getWorld().equals(location.getWorld())) {
            return false;
        } else {
            double chunkX = (double)(chunk.getX() * 16);
            double locationX = location.getX();
            double chunkZ = (double)(chunk.getZ() * 16);
            double locationZ = location.getZ();
            if (chunkX <= locationX && chunkX + (double)16.0F >= locationX) {
                return chunkZ <= locationZ && chunkZ + (double)16.0F >= locationZ;
            } else {
                return false;
            }
        }
    }

    public static boolean chunkAtLocationIsLoaded(Location location) {
        return location != null && location.getWorld() != null &&
                location.getWorld().isChunkLoaded(location.getBlock().getX() >> 4, location.getBlock().getZ() >> 4);
    }

    // NEW METHODS FOR STRING CONVERSION

    /**
     * Converts a location to a chunk string key in format "worldName:chunkX:chunkZ"
     * @param location The location to convert
     * @return String key representing the chunk, or null if location/world is null
     */
    public static String locationToChunkString(Location location) {
        if (location == null || location.getWorld() == null) return null;

        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        return location.getWorld().getName() + ":" + chunkX + ":" + chunkZ;
    }

    /**
     * Converts a chunk to a string key in format "worldName:chunkX:chunkZ"
     * @param chunk The chunk to convert
     * @return String key representing the chunk
     */
    public static String chunkToString(Chunk chunk) {
        return chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ();
    }

    /**
     * Converts world name and chunk coordinates to a string key
     * @param worldName Name of the world
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return String key in format "worldName:chunkX:chunkZ"
     */
    public static String coordinatesToChunkString(String worldName, int chunkX, int chunkZ) {
        return worldName + ":" + chunkX + ":" + chunkZ;
    }

    /**
     * Checks if a chunk string represents a loaded chunk
     * @param chunkString String in format "worldName:chunkX:chunkZ"
     * @return true if the chunk is loaded, false otherwise
     */
    public static boolean isChunkStringLoaded(String chunkString) {
        if (chunkString == null) return false;

        String[] parts = chunkString.split(":");
        if (parts.length != 3) return false;

        try {
            String worldName = parts[0];
            int chunkX = Integer.parseInt(parts[1]);
            int chunkZ = Integer.parseInt(parts[2]);

            World world = Bukkit.getWorld(worldName);
            return world != null && world.isChunkLoaded(chunkX, chunkZ);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Extracts world name from a chunk string
     * @param chunkString String in format "worldName:chunkX:chunkZ"
     * @return World name, or null if invalid format
     */
    public static String getWorldNameFromChunkString(String chunkString) {
        if (chunkString == null) return null;
        String[] parts = chunkString.split(":", 2); // Split into max 2 parts
        return parts.length >= 1 ? parts[0] : null;
    }

    /**
     * Extracts chunk X coordinate from a chunk string
     * @param chunkString String in format "worldName:chunkX:chunkZ"
     * @return Chunk X coordinate, or 0 if invalid format
     */
    public static int getChunkXFromChunkString(String chunkString) {
        if (chunkString == null) return 0;
        String[] parts = chunkString.split(":");
        if (parts.length != 3) return 0;
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Extracts chunk Z coordinate from a chunk string
     * @param chunkString String in format "worldName:chunkX:chunkZ"
     * @return Chunk Z coordinate, or 0 if invalid format
     */
    public static int getChunkZFromChunkString(String chunkString) {
        if (chunkString == null) return 0;
        String[] parts = chunkString.split(":");
        if (parts.length != 3) return 0;
        try {
            return Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}