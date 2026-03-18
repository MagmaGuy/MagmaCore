package com.magmaguy.magmacore.menus;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.Map;

public class AdvancedMenu {
    private final Player player;
    private final Map<Integer, MenuButton> advancedMenuItems = new HashMap<>();
    private final Inventory inventory;

    public AdvancedMenu(Player player, int size) {
        this.player = player;
        AdvancedMenuHandler.addAdvancedMenu(inventory = Bukkit.createInventory(player, size), this);
    }

    public void addAdvancedMenuItem(int slot, MenuButton advancedMenuItem) {
        advancedMenuItems.put(slot, advancedMenuItem);
        inventory.setItem(slot, advancedMenuItem.getItemStack());
    }

    public void removeAdvancedMenuItem(int slot, MenuButton advancedMenuItem) {
        advancedMenuItems.put(slot, advancedMenuItem);
        inventory.remove(advancedMenuItem.getItemStack());
    }

    public void openInventory(Player player) {
        player.openInventory(inventory);
    }

    public void run(int slot) {
        MenuButton advancedMenuItem = advancedMenuItems.get(slot);
        if (advancedMenuItem != null) advancedMenuItem.onClick(player);
    }
}
