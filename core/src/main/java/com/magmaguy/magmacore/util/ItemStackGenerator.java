package com.magmaguy.magmacore.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

public class ItemStackGenerator {

    private ItemStackGenerator() {
    }

    /**
     * Builds a player head with a display name and lore.
     * <p>
     * This used to resolve the head's skin from Mojang: one blocking call to api.mojang.com for the
     * UUID, then another to sessionserver.mojang.com for the texture. Two problems with that:
     * <p>
     * 1. It never worked. The resolved profile came from Bukkit.createPlayerProfile(uuid), which
     * leaves the profile name null, and the code then only applied the profile when
     * getName() != null. That guard could never pass, so every fetched texture was discarded.
     * <p>
     * 2. It was the single largest cost at server startup. The UUID lookup sat before the cache
     * check, so it ran on every call even on a cache hit, and neither call set a connect timeout.
     * Measured on a full-content server: ~19s inside EliteMobs' config load and ~7s inside
     * FreeMinecraftModels' menu setup, the latter on the main thread.
     * <p>
     * Since the textures never rendered, the calls and their profile cache are gone. Heads render
     * as the default player head, exactly as they did before. If skinned heads are wanted later
     * they must be resolved off the startup path and cached to disk, and the profile must be
     * created with a name via Bukkit.createPlayerProfile(uuid, username) so it actually applies.
     *
     * @param username retained so callers do not all have to change; currently unused
     */
    public static ItemStack generateSkullItemStack(String username, String name, List<String> lore) {
        ItemStack itemStack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) itemStack.getItemMeta();

        skullMeta.setDisplayName(ChatColorConverter.convert(name));
        skullMeta.setLore(ChatColorConverter.convert(lore));
        itemStack.setItemMeta(skullMeta);
        return itemStack;
    }

    public static ItemStack generateItemStack(ItemStack itemStack, String name, List<String> lore) {
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setDisplayName(ChatColorConverter.convert(name));
        itemMeta.setLore(ChatColorConverter.convert(lore));
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    public static ItemStack generateItemStack(Material material, String name, List<String> lore, int customModelID) {
        ItemStack itemStack = generateItemStack(material, ChatColorConverter.convert(name));
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setLore(ChatColorConverter.convert(lore));
        if (customModelID > 0)
            itemMeta.setCustomModelData(customModelID);
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    public static ItemStack generateItemStack(Material material, String name, List<String> lore, String namespacedKey) {
        ItemStack itemStack = generateItemStack(material, ChatColorConverter.convert(name));
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setLore(ChatColorConverter.convert(lore));
        if (!VersionChecker.serverVersionOlderThan(21, 4) && namespacedKey != null)
            try {
                itemMeta.setItemModel(NamespacedKey.fromString(namespacedKey));
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Failed to set item model for " + namespacedKey);
            }
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    public static ItemStack generateItemStack(Material material, String name, List<String> lore) {
        ItemStack itemStack = generateItemStack(material, ChatColorConverter.convert(name));
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setLore(ChatColorConverter.convert(lore));
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    public static ItemStack generateItemStack(Material material, String name) {
        ItemStack itemStack = generateItemStack(material);
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setDisplayName(ChatColorConverter.convert(name));
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    public static ItemStack generateItemStack(Material material) {
        if (material == null) material = Material.AIR;
        ItemStack itemStack = new ItemStack(material);
        if (material.equals(Material.AIR)) return itemStack;
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setDisplayName("");
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    public static ItemStack generateFlaglessItemStack(Material material, String name, List<String> loreList) {
        ItemStack itemStack = generateItemStack(material, name, loreList);
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        itemMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        itemMeta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

}
