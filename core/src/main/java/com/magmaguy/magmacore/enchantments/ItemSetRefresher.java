package com.magmaguy.magmacore.enchantments;

import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Redraws set lore after anything that can change what a player wears or carries. Every host registers
 * one; only the elected input owner redraws, a tick later, once per player however many events arrive.
 */
final class ItemSetRefresher implements Listener {
    private final Plugin plugin;
    private final String namespace;
    private final Set<UUID> pending = new HashSet<>();

    ItemSetRefresher(Plugin plugin, String namespace) {
        this.plugin = plugin;
        this.namespace = namespace;
    }

    private void schedule(HumanEntity entity) {
        if (!(entity instanceof Player player) || !plugin.isEnabled() || !pending.add(player.getUniqueId())) return;
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            pending.remove(id);
            Player current = Bukkit.getPlayer(id);
            if (current == null || !current.isOnline() || !EnchantmentInputs.elected(plugin, namespace)) return;
            ItemSets.refresh(current);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void click(InventoryClickEvent event) { schedule(event.getWhoClicked()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) { schedule(event.getWhoClicked()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void close(InventoryCloseEvent event) { schedule(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent event) { schedule(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) { schedule(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) { schedule(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) schedule(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void wornOut(PlayerItemBreakEvent event) { schedule(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void dispense(BlockDispenseArmorEvent event) {
        if (event.getTargetEntity() instanceof Player player) schedule(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void armorStand(PlayerArmorStandManipulateEvent event) { schedule(event.getPlayer()); }

    /** Right-clicking armor equips it without an inventory click; only set items need a redraw. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void interact(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(ItemSets.PRESENTATION)) schedule(event.getPlayer());
    }

    @EventHandler public void join(PlayerJoinEvent event) { schedule(event.getPlayer()); }

    @EventHandler public void respawn(PlayerRespawnEvent event) { schedule(event.getPlayer()); }
}
