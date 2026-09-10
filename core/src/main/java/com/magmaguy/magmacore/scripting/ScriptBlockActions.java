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

    /**
     * Removes an unchanged, authorized block synchronously, then emits the authored drop.
     * Null means intentional no drops. The child event can cancel or suppress drops.
     */
    public static boolean breakBlock(Player player, Block block, Material expected, ItemStack drop) {
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
        event.setDropItems(capturedDrop != null);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !player.isOnline() || !player.isValid() || player.isDead()
                || !player.getWorld().equals(block.getWorld())
                || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                || !original.equals(block.getBlockData().getAsString())
                || !LocationQueryRegistry.canBuild(player, block.getLocation())) return false;
        block.setType(Material.AIR);
        if (block.getType() != Material.AIR) return false;
        if (capturedDrop != null && event.isDropItems())
            block.getWorld().dropItemNaturally(block.getLocation().add(.5, .5, .5), capturedDrop);
        return true;
    }

    /** Synthetic mining remains cancellable but is not another physical tool input. */
    private static final class ScriptBreakEvent extends BlockBreakEvent {
        private ScriptBreakEvent(Block block, Player player) { super(block, player); }
    }
}
