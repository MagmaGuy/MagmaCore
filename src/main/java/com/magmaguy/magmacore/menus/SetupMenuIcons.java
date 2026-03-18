package com.magmaguy.magmacore.menus;

import com.magmaguy.magmacore.util.VersionChecker;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Shared setup-menu icon constants matching the EliteMobs menu visuals.
 * These use item_model when supported and gracefully fall back to the base item.
 */
public final class SetupMenuIcons {

    public static final String MODEL_LOCKED_UNLINKED = "elitemobs:ui/lockedunlinked";
    public static final String MODEL_LOCKED_UNPAID = "elitemobs:ui/lockedunpaid";
    public static final String MODEL_UNLOCKED = "elitemobs:ui/unlocked";
    public static final String MODEL_CHECKMARK = "elitemobs:ui/checkmark";
    public static final String MODEL_GRAY_X = "elitemobs:ui/gray_x";
    public static final String MODEL_UPDATE_UNLINKED = "elitemobs:ui/updateunlinked";
    public static final String MODEL_UPDATE_UNPAID = "elitemobs:ui/updateunpaid";
    public static final String MODEL_UPDATE = "elitemobs:ui/update";
    public static final String MODEL_RED_CROSS = "elitemobs:ui/redcross";
    public static final String MODEL_CROWN_YELLOW = "elitemobs:ui/yellowcrown";

    private SetupMenuIcons() {
    }

    public static void applyItemModel(ItemStack itemStack, String modelId) {
        if (itemStack == null || modelId == null || modelId.isEmpty()) return;
        if (VersionChecker.serverVersionOlderThan(21, 4)) return;
        ItemMeta itemMeta = itemStack.getItemMeta();
        if (itemMeta == null) return;
        try {
//            itemMeta.setItemModel(NamespacedKey.fromString(modelId));
//            itemStack.setItemMeta(itemMeta);
            // Temporarily disabled for the shared Nightbreak setup menus until
            // BetterStructures / FreeMinecraftModels ship their own menu icon models.
        } catch (Exception ignored) {
            // Keep the fallback material when the resource pack model is unavailable.
        }
    }
}
