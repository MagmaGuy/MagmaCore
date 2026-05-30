package com.magmaguy.magmacore.menus;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class NightbreakSetupIcons {
    public static final String MODEL_LOCKED_UNLINKED = "nightbreak:ui/lockedunlinked";
    public static final String MODEL_LOCKED_UNPAID = "nightbreak:ui/lockedunpaid";
    public static final String MODEL_UNLOCKED = "nightbreak:ui/unlocked";
    public static final String MODEL_CHECKMARK = "nightbreak:ui/checkmark";
    public static final String MODEL_GRAY_X = "nightbreak:ui/gray_x";
    public static final String MODEL_UPDATE_UNLINKED = "nightbreak:ui/updateunlinked";
    public static final String MODEL_UPDATE_UNPAID = "nightbreak:ui/updateunpaid";
    public static final String MODEL_UPDATE = "nightbreak:ui/update";
    public static final String MODEL_RED_CROSS = "nightbreak:ui/redcross";
    public static final String MODEL_CROWN_YELLOW = "nightbreak:ui/yellowcrown";

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
