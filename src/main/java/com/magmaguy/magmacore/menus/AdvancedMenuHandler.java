package com.magmaguy.magmacore.menus;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.Map;

public class AdvancedMenuHandler {
    private static Map<Inventory, AdvancedMenu> advancedMenus;

    public AdvancedMenuHandler() {
        advancedMenus = new HashMap<>();
    }

    public static void addAdvancedMenu(Inventory inventory, AdvancedMenu advancedMenu) {
        advancedMenus.put(inventory, advancedMenu);
    }

    public static class AdvancedMenuListeners implements Listener {
        @EventHandler
        public void onInventoryInteraction(InventoryClickEvent event) {
            AdvancedMenu advancedMenu = advancedMenus.get(event.getInventory());
            if (advancedMenu == null) return;
            event.setCancelled(true);
            advancedMenu.run(event.getSlot());
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            advancedMenus.remove(event.getInventory());
        }
    }
}
