package com.magmaguy.magmacore.util;

import com.magmaguy.magmacore.MagmaCore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.UUID;

/**
 * Manages temporary block placements that automatically revert after a set duration.
 * Blocks placed through this manager are tracked to prevent item drops on break
 * and are restored to their original state when the timer expires.
 */
public final class TemporaryBlockManager implements Listener {

    private static final HashSet<Block> temporaryBlocks = new HashSet<>();

    private TemporaryBlockManager() {}

    /**
     * Registers the block break and world unload listeners.
     * Call once during plugin enable.
     */
    public static void initialize(JavaPlugin plugin) {
        Bukkit.getPluginManager().registerEvents(new TemporaryBlockManager(), plugin);
    }

    /**
     * Places a temporary block that reverts after the given number of ticks.
     *
     * @param block               the block to replace
     * @param ticks               duration in ticks before reverting; if <= 0 the block is placed permanently
     * @param replacementMaterial the material to set
     */
    public static void addTemporaryBlock(Block block, int ticks, Material replacementMaterial) {
        BlockData previousBlockData = block.getBlockData().clone();
        if (temporaryBlocks.contains(block)) previousBlockData = null;
        temporaryBlocks.add(block);
        block.setType(replacementMaterial);
        if (ticks <= 0) return;
        UUID worldUUID = block.getWorld().getUID();
        BlockData finalPreviousBlockData = previousBlockData;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (Bukkit.getWorld(worldUUID) == null) return;
                temporaryBlocks.remove(block);
                if (!block.getBlockData().equals(finalPreviousBlockData))
                    if (finalPreviousBlockData != null)
                        block.setBlockData(finalPreviousBlockData);
                    else
                        block.setType(Material.AIR);
            }
        }.runTaskLater(MagmaCore.getInstance().getRequestingPlugin(), ticks);
    }

    /**
     * Places a temporary block, optionally only if the target block is air.
     *
     * @param block               the block to replace
     * @param ticks               duration in ticks before reverting
     * @param replacementMaterial the material to set
     * @param requireAir          if true, only place if the block is currently air
     */
    public static void addTemporaryBlock(Block block, int ticks, Material replacementMaterial, boolean requireAir) {
        if (requireAir && !block.getType().isAir()) return;
        addTemporaryBlock(block, ticks, replacementMaterial);
    }

    public static boolean isTemporaryBlock(Block block) {
        return temporaryBlocks.contains(block);
    }

    public static void removeTemporaryBlock(Block block) {
        temporaryBlocks.remove(block);
    }

    /**
     * Reverts all temporary blocks to air and clears tracking.
     * Call during plugin shutdown.
     */
    public static void shutdown() {
        for (Block block : temporaryBlocks)
            block.setType(Material.AIR);
        temporaryBlocks.clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isTemporaryBlock(event.getBlock())) return;
        event.setDropItems(false);
        removeTemporaryBlock(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        temporaryBlocks.removeIf(block -> block.getWorld().equals(event.getWorld()));
    }
}
