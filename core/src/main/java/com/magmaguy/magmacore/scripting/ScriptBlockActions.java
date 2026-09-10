package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.location.LocationQueryRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

/** Optional authored mining operation. Direct Lua world edits keep their existing semantics. */
public final class ScriptBlockActions {
    private ScriptBlockActions() { }

    /** Optional permanent authored placement, with the normal cancellable placement event. */
    public static boolean placeBlock(Player player, Block block, Material expected,
                                     org.bukkit.block.data.BlockData replacement, ItemStack source,
                                     org.bukkit.inventory.EquipmentSlot hand) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Script placement requires the server thread");
        if (player == null || !player.isOnline() || !player.isValid() || player.isDead() || block == null
                || !player.getWorld().equals(block.getWorld()) || expected == null || replacement == null
                || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                || block.getY() <= block.getWorld().getMinHeight() || block.getY() >= block.getWorld().getMaxHeight()
                || block.getType() != expected || block.getState() instanceof org.bukkit.block.TileState
                || !LocationQueryRegistry.canBuild(player, block.getLocation())) return false;
        var original = block.getState();
        String originalData = original.getBlockData().getAsString();
        String placedData = replacement.getAsString();
        if (originalData.equals(placedData)) return false;
        // Match native placement: listeners inspect the proposed block in the world.
        block.setBlockData(replacement, false);
        boolean accepted = false;
        try {
            var event = new ScriptPlaceEvent(block, original, block.getRelative(org.bukkit.block.BlockFace.DOWN),
                    source == null ? new ItemStack(Material.AIR) : source.clone(), player, hand);
            Bukkit.getPluginManager().callEvent(event);
            accepted = !event.isCancelled() && event.canBuild() && player.isOnline() && player.isValid()
                    && !player.isDead() && player.getWorld().equals(block.getWorld())
                    && block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                    && placedData.equals(block.getBlockData().getAsString())
                    && LocationQueryRegistry.canBuild(player, block.getLocation());
            return accepted;
        } finally {
            // A listener's independent replacement wins over our rollback.
            if (!accepted && block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                    && placedData.equals(block.getBlockData().getAsString()))
                original.update(true, false);
        }
    }

    private static final class ScriptPlaceEvent extends org.bukkit.event.block.BlockPlaceEvent {
        private ScriptPlaceEvent(Block block, org.bukkit.block.BlockState original, Block against,
                                 ItemStack item, Player player, org.bukkit.inventory.EquipmentSlot hand) {
            super(block, original, against, item, player, true, hand);
        }
    }

    /**
     * Removes an unchanged, authorized block synchronously, then emits the authored drop.
     * Null means intentional no drops. The child event can cancel or suppress drops.
     */
    public static boolean breakBlock(Player player, Block block, Material expected, ItemStack drop) {
        return breakBlock(player, block, expected, drop, null, true);
    }

    /** Natural loot uses the captured tool, after the same cancellable authorization as authored drops. */
    public static boolean breakNaturally(Player player, Block block, Material expected, ItemStack tool, boolean dropItems) {
        return breakBlock(player, block, expected, null, java.util.Objects.requireNonNull(tool, "tool").clone(), dropItems);
    }

    private static boolean breakBlock(Player player, Block block, Material expected, ItemStack drop,
                                      ItemStack tool, boolean dropItems) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Script mining requires the server thread");
        if (player == null || !player.isOnline() || !player.isValid() || player.isDead() || block == null
                || !player.getWorld().equals(block.getWorld()) || expected == null || expected.isAir()
                || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                || block.getY() < block.getWorld().getMinHeight() || block.getY() >= block.getWorld().getMaxHeight()
                || block.getType() != expected || block.getState() instanceof org.bukkit.block.TileState
                || !LocationQueryRegistry.canBuild(player, block.getLocation())) return false;
        ItemStack capturedDrop = drop == null ? null : drop.clone();
        if (capturedDrop != null && (capturedDrop.getAmount() < 1 || capturedDrop.getType().isAir()
                || !capturedDrop.getType().isItem())) throw new IllegalArgumentException("Invalid authored mining drop");
        String original = block.getBlockData().getAsString();
        BlockBreakEvent event = new ScriptBreakEvent(block, player);
        event.setDropItems(dropItems && (capturedDrop != null || tool != null));
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !player.isOnline() || !player.isValid() || player.isDead()
                || !player.getWorld().equals(block.getWorld())
                || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                || !original.equals(block.getBlockData().getAsString())
                || !LocationQueryRegistry.canBuild(player, block.getLocation())) return false;
        var drops = dropItems && event.isDropItems() ? tool != null ? block.getDrops(tool, player).stream().map(ItemStack::clone).toList()
                : capturedDrop == null ? java.util.List.<ItemStack>of() : java.util.List.of(capturedDrop)
                : java.util.List.<ItemStack>of();
        block.setType(Material.AIR);
        if (block.getType() != Material.AIR) return false;
        for (ItemStack result : drops)
            block.getWorld().dropItemNaturally(block.getLocation().add(.5, .5, .5), result);
        return true;
    }

    /** Synthetic mining remains cancellable but is not another physical tool input. */
    private static final class ScriptBreakEvent extends BlockBreakEvent {
        private ScriptBreakEvent(Block block, Player player) { super(block, player); }
    }
}
