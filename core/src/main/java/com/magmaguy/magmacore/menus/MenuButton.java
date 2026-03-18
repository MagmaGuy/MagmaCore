package com.magmaguy.magmacore.menus;

import com.magmaguy.magmacore.util.ItemStackGenerator;
import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public abstract class MenuButton {
    @Getter
    private ItemStack itemStack = null;

    public MenuButton(Material material, String name, List<String> lore) {
        itemStack = ItemStackGenerator.generateItemStack(material, name, lore);
    }

    public MenuButton(ItemStack itemStack) {
        this.itemStack = itemStack;
    }

    public MenuButton() {
    }

    public abstract void onClick(Player player);
}
