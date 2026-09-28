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
import org.bukkit.scheduler.BukkitTask;
import java.util.UUID;

/**
 * Manages temporary block placements that automatically revert after a set duration.
 * Blocks placed through this manager are tracked to prevent item drops on break
 * and are restored to their original state when the timer expires.
 */
public final class TemporaryBlockManager implements Listener {

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
        private BlockData replacement;
        private boolean closed;
        private final boolean legacyPlacement;
        private BukkitTask expiryTask;
        private long expiryGeneration;

        private OwnedBlock(Block block, BlockData replacement, org.bukkit.plugin.Plugin owner, boolean legacyPlacement) {
            key = BlockKey.of(block);
            this.owner = owner;
            original = block.getBlockData().clone();
            this.replacement = replacement.clone();
            this.legacyPlacement = legacyPlacement;
        }

        @Override public void close() {
            release(true);
        }

        private void release(boolean restore) {
            if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Block restoration requires the server thread");
            if (closed) return;
            closed = true;
            if (expiryTask != null) expiryTask.cancel();
            ownedBlocks.remove(key, this);
            World world = Bukkit.getWorld(key.world());
            if (world == null || !world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) return;
            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            boolean owned = block.getMetadata(OWNED_KEY).stream()
                    .anyMatch(value -> value.getOwningPlugin() == owner && token.equals(value.asString()));
            if (!owned) return;
            block.removeMetadata(OWNED_KEY, owner);
            if (restore && block.getBlockData().equals(replacement)) block.setBlockData(original, legacyPlacement);
        }
    }

    /** Preflight without loading chunks. BlockData restoration deliberately excludes block-entity contents. */
    public static boolean canOwn(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                && !block.hasMetadata(OWNED_KEY)
                && !(block.getState() instanceof org.bukkit.block.TileState);
    }

    public static OwnedBlock replaceOwned(Block block, BlockData replacement, org.bukkit.plugin.Plugin owner) {
        return replaceOwned(block, replacement, owner, false);
    }

    private static OwnedBlock replaceOwned(Block block, BlockData replacement, org.bukkit.plugin.Plugin owner,
                                           boolean legacyPlacement) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Block replacement requires the server thread");
        java.util.Objects.requireNonNull(replacement, "replacement");
        java.util.Objects.requireNonNull(owner, "owner");
        if (!owner.isEnabled() || !canOwn(block)) return null;
        OwnedBlock lease = new OwnedBlock(block, replacement, owner, legacyPlacement);
        block.setMetadata(OWNED_KEY, new org.bukkit.metadata.FixedMetadataValue(owner, lease.token));
        ownedBlocks.put(lease.key, lease);
        // Timed legacy placements have always notified physics, including fluid flow
        // and releasing a barrier beside fluid. Explicit owned leases remain inert.
        try { block.setBlockData(replacement, legacyPlacement); }
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
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Block replacement requires the server thread");
        OwnedBlock lease = ownedBlocks.get(BlockKey.of(block));
        if (lease != null && !lease.legacyPlacement) return;
        if (lease == null && block.hasMetadata(OWNED_KEY)) return;
        if (ticks <= 0) {
            if (lease != null) lease.release(false);
            block.setType(replacementMaterial);
            return;
        }
        if (lease != null && !block.getBlockData().equals(lease.replacement)) {
            lease.release(false);
            lease = null;
        }
        if (lease == null) {
            lease = replaceOwned(block, replacementMaterial.createBlockData(),
                    MagmaCore.getInstance().getRequestingPlugin(), true);
            if (lease == null) return;
        } else {
            if (lease.expiryTask != null) lease.expiryTask.cancel();
            block.setType(replacementMaterial);
            lease.replacement = block.getBlockData().clone();
        }
        OwnedBlock current = lease;
        long generation = ++lease.expiryGeneration;
        try {
            lease.expiryTask = Bukkit.getScheduler().runTaskLater(
                    MagmaCore.getInstance().getRequestingPlugin(), () -> {
                        if (current.expiryGeneration == generation) current.close();
                    }, ticks);
        } catch (RuntimeException failure) {
            current.close();
            throw failure;
        }
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
        return block.hasMetadata(OWNED_KEY);
    }

    public static void removeTemporaryBlock(Block block) {
        OwnedBlock lease = ownedBlocks.get(BlockKey.of(block));
        if (lease != null) lease.release(false);
    }

    /**
     * Restores still-owned temporary blocks and clears tracking.
     * Call during plugin shutdown.
     */
    public static void shutdown() {
        for (OwnedBlock lease : new java.util.ArrayList<>(ownedBlocks.values())) lease.close();
    }

    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isTemporaryBlock(event.getBlock())) return;
        event.setDropItems(false);
        removeTemporaryBlock(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.MONITOR)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        removeTemporaryBlock(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        for (OwnedBlock lease : new java.util.ArrayList<>(ownedBlocks.values()))
            if (lease.key.world().equals(event.getWorld().getUID())) lease.close();
    }

    @EventHandler(ignoreCancelled = true)
    public void onChunkUnload(org.bukkit.event.world.ChunkUnloadEvent event) {
        for (OwnedBlock lease : new java.util.ArrayList<>(ownedBlocks.values()))
            if (lease.key.world().equals(event.getWorld().getUID())
                    && (lease.key.x() >> 4) == event.getChunk().getX() && (lease.key.z() >> 4) == event.getChunk().getZ())
                lease.close();
    }
}
