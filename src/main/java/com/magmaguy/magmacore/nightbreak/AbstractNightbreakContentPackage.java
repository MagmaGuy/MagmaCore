package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.menus.ContentPackage;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public abstract class AbstractNightbreakContentPackage extends ContentPackage implements NightbreakManagedContent {
    @Getter
    @Setter
    private boolean outOfDate = false;
    @Getter
    @Setter
    private NightbreakAccount.AccessInfo cachedAccessInfo = null;

    protected abstract JavaPlugin getOwnerPlugin();

    protected abstract String getPluginDisplayName();

    protected abstract String getContentPageUrl();

    protected abstract List<String> getPackageDescription();

    protected abstract String getManualImportsFolderName();

    protected abstract String getManualReloadCommand();

    protected abstract void onDownloadStateSaved(Player player);

    @Override
    protected ItemStack getInstalledItemStack() {
        return NightbreakSetupMenuHelper.createInstalledItem(getDisplayName(), getPackageDescription());
    }

    @Override
    protected ItemStack getPartiallyInstalledItemStack() {
        return NightbreakSetupMenuHelper.createPartiallyInstalledItem(getDisplayName(), getPackageDescription());
    }

    @Override
    protected ItemStack getNotInstalledItemStack() {
        return NightbreakSetupMenuHelper.createNotInstalledItem(getDisplayName(), getPackageDescription());
    }

    @Override
    protected ItemStack getNotDownloadedItemStack() {
        return NightbreakSetupMenuHelper.createNotDownloadedItem(getDisplayName(), getPackageDescription(),
                getNightbreakSlug(), getCachedAccessInfo());
    }

    @Override
    protected ItemStack getNeedsAccessItemStack() {
        return NightbreakSetupMenuHelper.createNeedsAccessItem(getDisplayName(), getPackageDescription(), getCachedAccessInfo());
    }

    @Override
    protected ItemStack getOutOfDateUpdatableItemStack() {
        return NightbreakSetupMenuHelper.createOutOfDateUpdatableItem(getDisplayName(), getPackageDescription(), getNightbreakSlug());
    }

    @Override
    protected ItemStack getOutOfDateNoAccessItemStack() {
        return NightbreakSetupMenuHelper.createOutOfDateNoAccessItem(getDisplayName(), getPackageDescription());
    }

    @Override
    protected void doDownload(Player player) {
        player.closeInventory();
        String slug = getNightbreakSlug();
        if (slug == null || slug.isEmpty()) {
            NightbreakSetupMenuHelper.sendManualDownloadMessage(player, getDownloadLink(),
                    getManualImportsFolderName(), getManualReloadCommand());
            return;
        }

        if (!NightbreakAccount.hasToken()) {
            NightbreakSetupMenuHelper.sendNoTokenPrompt(player, getPluginDisplayName(), getContentPageUrl());
            return;
        }

        JavaPlugin plugin = getOwnerPlugin();
        Logger.sendSimpleMessage(player, "&aChecking Nightbreak access for &2" + getDisplayName() + "&a...");
        NightbreakContentManager.checkAccessAsync(plugin, slug, accessInfo -> {
            setCachedAccessInfo(accessInfo);
            if (!player.isOnline()) return;
            if (accessInfo == null) {
                Logger.sendSimpleMessage(player, "&cFailed to contact Nightbreak for access information.");
                return;
            }
            if (!accessInfo.hasAccess) {
                doShowAccessInfo(player);
                return;
            }

            File importsFolder = new File(plugin.getDataFolder(), "imports");
            if (!importsFolder.exists()) importsFolder.mkdirs();
            NightbreakContentManager.downloadAsync(plugin, slug, importsFolder, player, success -> {
                if (!player.isOnline() || !success) return;
                enableAfterDownload().whenComplete((ignored, throwable) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (throwable != null) {
                                throwable.printStackTrace();
                                Logger.sendSimpleMessage(player, "&cFailed to update " + getPluginDisplayName() + " package state. Check the console.");
                                return;
                            }
                            onDownloadStateSaved(player);
                        }));
            });
        });
    }

    @Override
    protected void doShowAccessInfo(Player player) {
        NightbreakSetupMenuHelper.sendAccessInfo(player, getDisplayName(), getCachedAccessInfo(), getContentPageUrl());
    }

    @Override
    protected ContentState getContentState() {
        boolean downloaded = isDownloaded();
        boolean installed = isInstalled();
        boolean hasNightbreakSlug = getNightbreakSlug() != null && !getNightbreakSlug().isEmpty();

        if (installed && isOutOfDate()) {
            if (hasNightbreakSlug && NightbreakAccount.hasToken() && getCachedAccessInfo() != null && !getCachedAccessInfo().hasAccess) {
                return ContentState.OUT_OF_DATE_NO_ACCESS;
            }
            return ContentState.OUT_OF_DATE_UPDATABLE;
        }
        if (installed) return ContentState.INSTALLED;
        if (downloaded) return ContentState.NOT_INSTALLED;
        if (hasNightbreakSlug && NightbreakAccount.hasToken() && getCachedAccessInfo() != null && !getCachedAccessInfo().hasAccess) {
            return ContentState.NEEDS_ACCESS;
        }
        return ContentState.NOT_DOWNLOADED;
    }

    protected void handleStateSave(Player player,
                                   CompletableFuture<Void> future,
                                   Runnable onSuccess,
                                   String failureMessage) {
        Bukkit.getScheduler().runTaskAsynchronously(getOwnerPlugin(), () ->
                future.whenComplete((ignored, throwable) ->
                        Bukkit.getScheduler().runTask(getOwnerPlugin(), () -> {
                            if (throwable != null) {
                                throwable.printStackTrace();
                                if (player.isOnline()) {
                                    Logger.sendSimpleMessage(player, failureMessage);
                                }
                                return;
                            }
                            onSuccess.run();
                        })));
    }
}
