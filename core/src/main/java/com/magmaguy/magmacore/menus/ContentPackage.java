package com.magmaguy.magmacore.menus;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public abstract class ContentPackage extends MenuButton {
    public ContentPackage() {
    }

    public ItemStack getItemstack() {
        if (isComingSoon()) return getComingSoonItemStack();
        return switch (getContentState()) {
            case INSTALLED -> getInstalledItemStack();
            case PARTIALLY_INSTALLED -> getPartiallyInstalledItemStack();
            case NOT_INSTALLED -> getNotInstalledItemStack();
            case NOT_DOWNLOADED -> getNotDownloadedItemStack();
            case NEEDS_ACCESS -> getNeedsAccessItemStack();
            case OUT_OF_DATE_UPDATABLE -> getOutOfDateUpdatableItemStack();
            case OUT_OF_DATE_NO_ACCESS -> getOutOfDateNoAccessItemStack();
        };
    }

    protected abstract ItemStack getInstalledItemStack();

    protected abstract ItemStack getPartiallyInstalledItemStack();

    protected abstract ItemStack getNotInstalledItemStack();

    protected abstract ItemStack getNotDownloadedItemStack();

    protected abstract void doInstall(Player player);

    protected abstract void doUninstall(Player player);

    protected abstract void doDownload(Player player);

    protected abstract void doShowAccessInfo(Player player);

    protected abstract ItemStack getNeedsAccessItemStack();

    protected abstract ItemStack getOutOfDateUpdatableItemStack();

    protected abstract ItemStack getOutOfDateNoAccessItemStack();

    protected abstract ContentState getContentState();

    /**
     * True when this package is not released yet. The setup menu lists it under Coming soon instead of with the
     * rest, and clicking it only says so.
     */
    public boolean isComingSoon() {
        return false;
    }

    protected ItemStack getComingSoonItemStack() {
        return getNotDownloadedItemStack();
    }

    protected void doShowComingSoon(Player player) {
    }

    @Override
    public void onClick(Player player) {
        player.closeInventory();
        if (isComingSoon()) {
            doShowComingSoon(player);
            return;
        }
        switch (getContentState()) {
            case INSTALLED -> doUninstall(player);
            case PARTIALLY_INSTALLED -> doDownload(player);
            case NOT_INSTALLED -> doInstall(player);
            case NOT_DOWNLOADED -> doDownload(player);
            case NEEDS_ACCESS -> doShowAccessInfo(player);
            case OUT_OF_DATE_UPDATABLE -> doDownload(player);
            case OUT_OF_DATE_NO_ACCESS -> doShowAccessInfo(player);
        }
    }

    public enum ContentState {
        INSTALLED,
        PARTIALLY_INSTALLED,
        NOT_INSTALLED,
        NOT_DOWNLOADED,
        NEEDS_ACCESS,  // User doesn't have Nightbreak access to this content
        OUT_OF_DATE_UPDATABLE,  // Content has update and user can auto-update (has access)
        OUT_OF_DATE_NO_ACCESS   // Content has update but user needs to purchase/get access
    }
}
