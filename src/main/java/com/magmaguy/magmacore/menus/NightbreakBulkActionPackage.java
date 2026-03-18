package com.magmaguy.magmacore.menus;

import com.magmaguy.magmacore.nightbreak.NightbreakAccount;
import com.magmaguy.magmacore.nightbreak.NightbreakManagedContent;
import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.SpigotMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NightbreakBulkActionPackage<T extends NightbreakManagedContent> extends ContentPackage {
    private final String pluginDisplayName;
    private final String contentPageUrl;
    private final String commandToRun;
    private final List<T> allPackages;
    private final ItemStack displayIcon;

    public NightbreakBulkActionPackage(String pluginDisplayName,
                                       String contentPageUrl,
                                       String commandToRun,
                                       List<T> allPackages) {
        this.pluginDisplayName = pluginDisplayName;
        this.contentPageUrl = contentPageUrl;
        this.commandToRun = commandToRun;
        this.allPackages = allPackages;
        this.displayIcon = buildIcon();
    }

    private ItemStack buildIcon() {
        String iconModel;
        Material baseMaterial;
        String displayName;
        List<String> lore;

        if (!NightbreakAccount.hasToken()) {
            iconModel = NightbreakSetupIcons.MODEL_RED_CROSS;
            baseMaterial = Material.RED_STAINED_GLASS_PANE;
            displayName = "&cDownload All";
            lore = List.of("&7No Nightbreak token linked.", "&7Click for setup instructions.");
        } else {
            long notDownloadedCount = countNotDownloaded();
            long outdatedCount = countOutdated();

            if (notDownloadedCount > 0 && outdatedCount > 0) {
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

        return NightbreakSetupVisuals.bulkActionItem(displayName, lore, baseMaterial, iconModel);
    }

    private long countNotDownloaded() {
        Set<String> seenSlugs = new HashSet<>();
        long count = 0;
        for (T pkg : allPackages) {
            String slug = pkg.getNightbreakSlug();
            if (slug == null || slug.isEmpty()) continue;
            if (seenSlugs.contains(slug)) continue;
            if (!pkg.isDownloaded()
                    && pkg.getCachedAccessInfo() != null
                    && pkg.getCachedAccessInfo().hasAccess) {
                count++;
                seenSlugs.add(slug);
            }
        }
        return count;
    }

    private long countOutdated() {
        Set<String> seenSlugs = new HashSet<>();
        long count = 0;
        for (T pkg : allPackages) {
            if (!pkg.isOutOfDate()) continue;
            String slug = pkg.getNightbreakSlug();
            if (slug == null || slug.isEmpty()) continue;
            if (pkg.getCachedAccessInfo() != null && !pkg.getCachedAccessInfo().hasAccess) continue;
            if (seenSlugs.add(slug)) count++;
        }
        return count;
    }

    @Override
    public ItemStack getItemstack() {
        return displayIcon;
    }

    @Override
    public void onClick(Player player) {
        player.closeInventory();

        if (!NightbreakAccount.hasToken()) {
            player.sendMessage(ChatColorConverter.convert("&8&m----------------------------------------------------"));
            player.sendMessage(ChatColorConverter.convert("&eLink your Nightbreak account first to install " + pluginDisplayName + " content in-game."));
            player.spigot().sendMessage(
                    SpigotMessage.simpleMessage("&6Account page: "),
                    SpigotMessage.hoverLinkMessage("&9&nhttps://nightbreak.io/account/", "&7Click to open Nightbreak account", "https://nightbreak.io/account/"));
            player.spigot().sendMessage(
                    SpigotMessage.simpleMessage("&6Content page: "),
                    SpigotMessage.hoverLinkMessage("&9&n" + contentPageUrl, "&7Click to browse content", contentPageUrl));
            player.sendMessage(ChatColorConverter.convert("&aUse &2/nightbreaklogin <token> &ato link your account, then click this button again."));
            player.sendMessage(ChatColorConverter.convert("&8&m----------------------------------------------------"));
            return;
        }

        boolean hasNotDownloaded = false;
        boolean hasOutdated = false;
        for (T pkg : allPackages) {
            String slug = pkg.getNightbreakSlug();
            if (slug == null || slug.isEmpty()) continue;
            if (!pkg.isDownloaded() && pkg.getCachedAccessInfo() != null && pkg.getCachedAccessInfo().hasAccess) {
                hasNotDownloaded = true;
            }
            if (pkg.isOutOfDate()) {
                hasOutdated = true;
            }
            if (hasNotDownloaded && hasOutdated) break;
        }

        if (!hasNotDownloaded && !hasOutdated) {
            player.sendMessage(ChatColorConverter.convert("&a[" + pluginDisplayName + "] No new content to download! All available packages are already downloaded."));
            return;
        }

        Bukkit.dispatchCommand(player, commandToRun);
    }

    @Override protected ItemStack getInstalledItemStack() { return displayIcon; }
    @Override protected ItemStack getPartiallyInstalledItemStack() { return displayIcon; }
    @Override protected ItemStack getNotInstalledItemStack() { return displayIcon; }
    @Override protected ItemStack getNotDownloadedItemStack() { return displayIcon; }
    @Override protected ItemStack getNeedsAccessItemStack() { return displayIcon; }
    @Override protected ItemStack getOutOfDateUpdatableItemStack() { return displayIcon; }
    @Override protected ItemStack getOutOfDateNoAccessItemStack() { return displayIcon; }
    @Override protected void doInstall(Player player) {}
    @Override protected void doUninstall(Player player) {}
    @Override protected void doDownload(Player player) {}
    @Override protected void doShowAccessInfo(Player player) {}
    @Override protected ContentState getContentState() { return ContentState.INSTALLED; }
}
