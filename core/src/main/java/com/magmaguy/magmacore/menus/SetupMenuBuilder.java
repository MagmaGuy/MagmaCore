package com.magmaguy.magmacore.menus;

import com.magmaguy.magmacore.util.ItemStackGenerator;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Fluent wrapper around {@link SetupMenu}. Removes the boilerplate of constructing the
 * info button, filter list and bulk-action package by hand at every call site.
 *
 * <p>Method chaining returns {@code this}. {@link #open()} builds and shows the menu.
 */
public class SetupMenuBuilder {

    public static final String MENU_SKIN_PREFIX = "󰻱󰸋󰻵          ";

    private final JavaPlugin plugin;
    private final Player player;
    private final List<SetupMenu.SetupMenuFilter> filters = new ArrayList<>();

    private String title = "Setup menu";
    private String titleIconPrefix = MENU_SKIN_PREFIX;
    private MenuButton infoButton = null;
    private List<? extends ContentPackage> packages = null;
    private final List<ContentPackage> appendedPackages = new ArrayList<>();

    public SetupMenuBuilder(JavaPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    public SetupMenuBuilder title(String title) {
        this.title = title;
        return this;
    }

    public SetupMenuBuilder titleIconPrefix(String prefix) {
        this.titleIconPrefix = prefix;
        return this;
    }

    public SetupMenuBuilder infoButton(MenuButton infoButton) {
        this.infoButton = infoButton;
        return this;
    }

    public SetupMenuBuilder packages(List<? extends ContentPackage> packages) {
        this.packages = packages;
        return this;
    }

    public SetupMenuBuilder appendPackage(ContentPackage extraPackage) {
        if (extraPackage != null) {
            this.appendedPackages.add(extraPackage);
        }
        return this;
    }

    public SetupMenuBuilder addFilter(Material material, String name, Predicate<? extends ContentPackage> predicate) {
        ItemStack filterItem = ItemStackGenerator.generateItemStack(material, name);
        List<ContentPackage> filtered = filterPackages(predicate);
        filters.add(new SetupMenu.SetupMenuFilter(filterItem, filtered));
        return this;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<ContentPackage> filterPackages(Predicate<? extends ContentPackage> predicate) {
        List<ContentPackage> result = new ArrayList<>();
        if (packages == null) return result;
        Predicate rawPredicate = predicate;
        for (ContentPackage pkg : packages) {
            if (rawPredicate.test(pkg)) result.add(pkg);
        }
        return result;
    }

    public SetupMenu open() {
        if (packages == null) {
            Logger.warn("SetupMenuBuilder.open() called without packages set; aborting menu open.");
            return null;
        }
        if (infoButton == null) {
            Logger.warn("SetupMenuBuilder.open() called without infoButton set; aborting menu open.");
            return null;
        }

        List<ContentPackage> finalPackages = new ArrayList<>(packages);
        finalPackages.addAll(appendedPackages);

        String finalTitle = title;
        if (titleIconPrefix != null && !titleIconPrefix.isEmpty()) {
            finalTitle = ChatColor.WHITE + titleIconPrefix + finalTitle;
        }

        return new SetupMenu(plugin, player, infoButton, finalPackages, filters, finalTitle);
    }
}
