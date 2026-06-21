package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.menus.ContentPackage;
import com.magmaguy.magmacore.menus.NightbreakSetupIcons;
import com.magmaguy.magmacore.util.ItemStackGenerator;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Shared setup-menu "Download All" package matching the EliteMobs behavior.
 */
public class DownloadAllContentPackage<T extends NightbreakManagedContent> extends ContentPackage {
    private final Supplier<Collection<T>> packagesSupplier;
    private final String pluginName;
    private final String contentUrl;
    private final String downloadAllCommand;

    public DownloadAllContentPackage(Supplier<Collection<T>> packagesSupplier,
                                     String pluginName,
                                     String contentUrl,
                                     String downloadAllCommand) {
        this.packagesSupplier = packagesSupplier;
        this.pluginName = pluginName;
        this.contentUrl = contentUrl;
        this.downloadAllCommand = downloadAllCommand == null ? "" : downloadAllCommand.trim();
    }

    @Override
    public ItemStack getItemstack() {
        Collection<T> allPackages = packagesSupplier.get();
        String iconModel;
        Material baseMaterial;
        String displayName;
        List<String> lore;

        if (!NightbreakAccount.hasToken()) {
            iconModel = NightbreakSetupIcons.MODEL_RED_CROSS;
            baseMaterial = Material.RED_STAINED_GLASS_PANE;
            displayName = includesPluginUpdate() ? "&cDownload Everything" : "&cDownload All Content";
            lore = List.of("&7No account token linked.", "&7Click for setup instructions.");
        } else if (NightbreakAccount.hasAuthFailure()) {
            iconModel = NightbreakSetupIcons.MODEL_RED_CROSS;
            baseMaterial = Material.RED_STAINED_GLASS_PANE;
            displayName = "&eUpdate Account Token";
            lore = List.of("&7Your saved account token needs", "&7to be updated before downloads work.");
        } else {
            long notDownloadedCount = countNotDownloaded(allPackages);
            long outdatedCount = countOutdated(allPackages);

            if (includesPluginUpdate()) {
                iconModel = NightbreakSetupIcons.MODEL_CROWN_YELLOW;
                baseMaterial = Material.YELLOW_STAINED_GLASS_PANE;
                displayName = "&eDownload / Update Everything";
                lore = List.of(
                        "&7Checks for a plugin update,",
                        "&7then downloads and updates content.",
                        "&7Restart the server to use plugin updates.");
            } else if (notDownloadedCount > 0 && outdatedCount > 0) {
                iconModel = NightbreakSetupIcons.MODEL_CROWN_YELLOW;
                baseMaterial = Material.YELLOW_STAINED_GLASS_PANE;
                displayName = "&eDownload & Update All";
                lore = List.of(
                        "&7Click to download &e" + notDownloadedCount + " &7new package" + (notDownloadedCount != 1 ? "s" : ""),
                        "&7and update &e" + outdatedCount + " &7outdated package" + (outdatedCount != 1 ? "s" : "") + ".");
            } else if (notDownloadedCount > 0) {
                iconModel = NightbreakSetupIcons.MODEL_CROWN_YELLOW;
                baseMaterial = Material.YELLOW_STAINED_GLASS_PANE;
                displayName = "&eDownload All";
                lore = List.of("&7Click to download &e" + notDownloadedCount + " &7available package" + (notDownloadedCount != 1 ? "s" : "") + ".");
            } else if (outdatedCount > 0) {
                iconModel = NightbreakSetupIcons.MODEL_CROWN_YELLOW;
                baseMaterial = Material.YELLOW_STAINED_GLASS_PANE;
                displayName = "&eUpdate All";
                lore = List.of("&7Click to update &e" + outdatedCount + " &7outdated package" + (outdatedCount != 1 ? "s" : "") + ".");
            } else {
                iconModel = NightbreakSetupIcons.MODEL_CHECKMARK;
                baseMaterial = Material.LIME_STAINED_GLASS_PANE;
                displayName = "&aAll Content Up To Date";
                lore = List.of("&7All available content is downloaded", "&7and up to date!");
            }
        }

        ItemStack itemStack = ItemStackGenerator.generateItemStack(baseMaterial, displayName, lore);
        NightbreakSetupIcons.applyItemModel(itemStack, iconModel);
        return itemStack;
    }

    private long countNotDownloaded(Collection<T> allPackages) {
        Set<String> seenSlugs = new HashSet<>();
        long count = 0;
        for (T contentPackage : allPackages) {
            String slug = contentPackage.getNightbreakSlug();
            if (slug == null || slug.isEmpty()) continue;
            if (!seenSlugs.add(slug)) continue;
            if (!contentPackage.isDownloaded()
                    && (contentPackage.getCachedAccessInfo() == null
                    || contentPackage.getCachedAccessInfo().hasAccess)) {
                count++;
            }
        }
        return count;
    }

    private long countOutdated(Collection<T> allPackages) {
        Set<String> seenSlugs = new HashSet<>();
        long count = 0;
        for (T contentPackage : allPackages) {
            if (!contentPackage.isOutOfDate()) continue;
            String slug = contentPackage.getNightbreakSlug();
            if (slug == null || slug.isEmpty()) continue;
            if (contentPackage.getCachedAccessInfo() != null && !contentPackage.getCachedAccessInfo().hasAccess) continue;
            if (seenSlugs.add(slug)) count++;
        }
        return count;
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

        boolean hasNotDownloaded = false;
        boolean hasOutdated = false;
        for (T contentPackage : packagesSupplier.get()) {
            String slug = contentPackage.getNightbreakSlug();
            if (slug == null || slug.isEmpty()) continue;
            if (!contentPackage.isDownloaded()
                    && (contentPackage.getCachedAccessInfo() == null
                    || contentPackage.getCachedAccessInfo().hasAccess)) {
                hasNotDownloaded = true;
            }
            if (contentPackage.isOutOfDate()
                    && (contentPackage.getCachedAccessInfo() == null
                    || contentPackage.getCachedAccessInfo().hasAccess)) {
                hasOutdated = true;
            }
            if (hasNotDownloaded && hasOutdated) break;
        }

        if (includesPluginUpdate()) {
            Bukkit.dispatchCommand(player, downloadAllCommand);
            return;
        }

        if (!hasNotDownloaded && !hasOutdated) {
            Logger.sendSimpleMessage(player, "&aAll available " + pluginName + " content is already downloaded and up to date.");
            return;
        }

        Bukkit.dispatchCommand(player, downloadAllCommand);
    }

    private boolean includesPluginUpdate() {
        return downloadAllCommand.endsWith(" downloadall");
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
