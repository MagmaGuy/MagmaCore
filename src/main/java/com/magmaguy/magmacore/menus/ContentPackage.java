package com.magmaguy.magmacore.menus;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public abstract class ContentPackage extends MenuButton {
    public ContentPackage() {
    }

    public ItemStack getItemstack() {
        return switch (getContentState()) {
            case INSTALLED -> getInstalledItemStack();
            case PARTIALLY_INSTALLED -> getPartiallyInstalledItemStack();
            case NOT_INSTALLED -> getNotInstalledItemStack();
            case NOT_DOWNLOADED -> getNotDownloadedItemStack();
        };
    }

    protected abstract ItemStack getInstalledItemStack();

    protected abstract ItemStack getPartiallyInstalledItemStack();

    protected abstract ItemStack getNotInstalledItemStack();

    protected abstract ItemStack getNotDownloadedItemStack();

    protected abstract void doInstall(Player player);

    protected abstract void doUninstall(Player player);

    protected abstract void doDownload(Player player);

    protected abstract ContentState getContentState();

    public enum ContentState {
        INSTALLED,
        PARTIALLY_INSTALLED,
        NOT_INSTALLED,
        NOT_DOWNLOADED
    }

    @Override
    public void onClick(Player player) {
        switch (getContentState()) {
            case INSTALLED -> doUninstall(player);
            case PARTIALLY_INSTALLED -> doDownload(player);
            case NOT_INSTALLED -> doInstall(player);
            case NOT_DOWNLOADED -> doDownload(player);
        }
    }
}
