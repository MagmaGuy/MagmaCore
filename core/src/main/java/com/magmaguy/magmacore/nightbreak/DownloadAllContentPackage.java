package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.menus.ContentPackage;
import com.magmaguy.magmacore.menus.NightbreakSetupIcons;
import com.magmaguy.magmacore.util.ItemStackGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * Shared setup-menu "Download All" package matching the EliteMobs behavior.
 */
public class DownloadAllContentPackage<T extends NightbreakManagedContent> extends ContentPackage {
    private final String pluginName;
    private final String contentUrl;
    private final String downloadAllCommand;

    // Every caller passes a "<command> downloadall" command, which also checks for a plugin update,
    // so the button never counts packages and the supplier is not read.
    public DownloadAllContentPackage(Supplier<Collection<T>> packagesSupplier,
                                     String pluginName,
                                     String contentUrl,
                                     String downloadAllCommand) {
        this.pluginName = pluginName;
        this.contentUrl = contentUrl;
        this.downloadAllCommand = downloadAllCommand == null ? "" : downloadAllCommand.trim();
    }

    @Override
    public ItemStack getItemstack() {
        String iconModel;
        Material baseMaterial;
        String displayName;
        List<String> lore;

        if (!NightbreakAccount.hasToken()) {
            iconModel = NightbreakSetupIcons.MODEL_RED_CROSS;
            baseMaterial = Material.RED_STAINED_GLASS_PANE;
            displayName = "&cDownload Everything";
            lore = List.of("&7No account token linked.", "&7Click for setup instructions.");
        } else if (NightbreakAccount.hasAuthFailure()) {
            iconModel = NightbreakSetupIcons.MODEL_RED_CROSS;
            baseMaterial = Material.RED_STAINED_GLASS_PANE;
            displayName = "&eUpdate Account Token";
            lore = List.of("&7Your saved account token needs", "&7to be updated before downloads work.");
        } else {
            iconModel = NightbreakSetupIcons.MODEL_CROWN_YELLOW;
            baseMaterial = Material.YELLOW_STAINED_GLASS_PANE;
            displayName = "&eDownload / Update Everything";
            lore = List.of(
                    "&7Checks for a plugin update,",
                    "&7then downloads and updates content.",
                    "&7Restart the server to use plugin updates.");
        }

        ItemStack itemStack = ItemStackGenerator.generateItemStack(baseMaterial, displayName, lore);
        NightbreakSetupIcons.applyItemModel(itemStack, iconModel);
        return itemStack;
    }

    @Override
    public void onClick(Player player) {
        player.closeInventory();
        if (!NightbreakAccount.hasToken()) {
            NightbreakSetupMenuHelper.sendNoTokenPrompt(player, pluginName, contentUrl);
            return;
        }
        if (NightbreakAccount.hasAuthFailure()) {
            NightbreakSetupMenuHelper.sendTokenUpdatePrompt(player, pluginName);
            return;
        }
        Bukkit.dispatchCommand(player, downloadAllCommand);
    }

    @Override protected ItemStack getInstalledItemStack() { return getItemstack(); }
    @Override protected ItemStack getPartiallyInstalledItemStack() { return getItemstack(); }
    @Override protected ItemStack getNotInstalledItemStack() { return getItemstack(); }
    @Override protected ItemStack getNotDownloadedItemStack() { return getItemstack(); }
    @Override protected ItemStack getNeedsAccessItemStack() { return getItemstack(); }
    @Override protected ItemStack getOutOfDateUpdatableItemStack() { return getItemstack(); }
    @Override protected ItemStack getOutOfDateNoAccessItemStack() { return getItemstack(); }
    @Override protected void doInstall(Player player) { }
    @Override protected void doUninstall(Player player) { }
    @Override protected void doDownload(Player player) { }
    @Override protected void doShowAccessInfo(Player player) { }
    @Override protected ContentState getContentState() { return ContentState.INSTALLED; }
}
