package com.magmaguy.magmacore.menus;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class NightbreakSetupIcons {
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

    private NightbreakSetupIcons() {
    }

    public static void applyItemModel(ItemStack itemStack, String modelId) {
        try {
            ItemMeta itemMeta = itemStack.getItemMeta();
            if (itemMeta == null) return;
            itemMeta.setItemModel(NamespacedKey.fromString(modelId));
            itemStack.setItemMeta(itemMeta);
        } catch (Exception ignored) {
        }
    }
}
