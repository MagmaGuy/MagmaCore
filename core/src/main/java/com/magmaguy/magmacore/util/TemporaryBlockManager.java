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
    private static final String OWNED_KEY = "nightbreak_owned_temporary_block";
    private static final java.util.Map<BlockKey, OwnedBlock> ownedBlocks = new java.util.HashMap<>();

    private record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Block block) { return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); }
    }

    /** Optional conditional restoration, shared across shaded consumers through Bukkit metadata. */
    public static final class OwnedBlock implements AutoCloseable {
        private final BlockKey key;
        private final org.bukkit.plugin.Plugin owner;
        private final String token = UUID.randomUUID().toString();
        private final BlockData original;
        private final BlockData replacement;
        private boolean closed;

        private OwnedBlock(Block block, BlockData replacement, org.bukkit.plugin.Plugin owner) {
            key = BlockKey.of(block);
            this.owner = owner;
            original = block.getBlockData().clone();
            this.replacement = replacement.clone();
        }

        @Override public void close() {
            if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Block restoration requires the server thread");
            if (closed) return;
            closed = true;
            ownedBlocks.remove(key, this);
            World world = Bukkit.getWorld(key.world());
            if (world == null || !world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) return;
            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            boolean owned = block.getMetadata(OWNED_KEY).stream()
                    .anyMatch(value -> value.getOwningPlugin() == owner && token.equals(value.asString()));
            if (!owned) return;
            block.removeMetadata(OWNED_KEY, owner);
            if (block.getBlockData().equals(replacement)) block.setBlockData(original, false);
        }
    }

    /** Preflight without loading chunks. BlockData restoration deliberately excludes block-entity contents. */
    public static boolean canOwn(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                && !temporaryBlocks.contains(block) && !block.hasMetadata(OWNED_KEY)
                && !(block.getState() instanceof org.bukkit.block.TileState);
    }

    public static OwnedBlock replaceOwned(Block block, BlockData replacement, org.bukkit.plugin.Plugin owner) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Block replacement requires the server thread");
        java.util.Objects.requireNonNull(replacement, "replacement");
        java.util.Objects.requireNonNull(owner, "owner");
        if (!owner.isEnabled() || !canOwn(block)) return null;
        OwnedBlock lease = new OwnedBlock(block, replacement, owner);
        block.setMetadata(OWNED_KEY, new org.bukkit.metadata.FixedMetadataValue(owner, lease.token));
        ownedBlocks.put(lease.key, lease);
        try { block.setBlockData(replacement, false); }
        catch (RuntimeException failure) { lease.close(); throw failure; }
        return lease;
    }

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
        if (block.hasMetadata(OWNED_KEY)) return;
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
        return temporaryBlocks.contains(block) || block.hasMetadata(OWNED_KEY);
    }

    public static void removeTemporaryBlock(Block block) {
        temporaryBlocks.remove(block);
    }

    /**
     * Reverts all temporary blocks to air and clears tracking.
     * Call during plugin shutdown.
     */
    public static void shutdown() {
        for (OwnedBlock lease : new java.util.ArrayList<>(ownedBlocks.values())) lease.close();
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
        for (OwnedBlock lease : new java.util.ArrayList<>(ownedBlocks.values()))
            if (lease.key.world().equals(event.getWorld().getUID())) lease.close();
        temporaryBlocks.removeIf(block -> block.getWorld().equals(event.getWorld()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onChunkUnload(org.bukkit.event.world.ChunkUnloadEvent event) {
        for (OwnedBlock lease : new java.util.ArrayList<>(ownedBlocks.values()))
            if (lease.key.world().equals(event.getWorld().getUID())
                    && (lease.key.x() >> 4) == event.getChunk().getX() && (lease.key.z() >> 4) == event.getChunk().getZ())
                lease.close();
    }
}
