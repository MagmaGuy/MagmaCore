package com.magmaguy.magmacore.menus;

import com.magmaguy.magmacore.util.VersionChecker;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.function.BooleanSupplier;

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

    private static volatile BooleanSupplier additionalResourcePackAvailable = () -> false;

    private NightbreakSetupIcons() {
    }

    /**
     * Lets a host plugin report a resource-pack delivery path other than
     * ResourcePackManager. The supplier is evaluated lazily so hosts can wire
     * it before their configuration has finished loading.
     */
    public static void setAdditionalResourcePackAvailableSupplier(BooleanSupplier supplier) {
        additionalResourcePackAvailable = supplier == null ? () -> false : supplier;
    }

    static boolean hasUsableResourcePack() {
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("ResourcePackManager")) return true;
        } catch (Exception | LinkageError ignored) {
            // A server is not available in lightweight tests; consult the host hook below.
        }

        try {
            return additionalResourcePackAvailable.getAsBoolean();
        } catch (Exception | LinkageError ignored) {
            return false;
        }
    }

    public static void applyItemModel(ItemStack itemStack, String modelId) {
        if (itemStack == null || modelId == null || modelId.isBlank()) return;
        if (VersionChecker.serverVersionOlderThan(21, 4)) return;
        if (!hasUsableResourcePack()) return;

        try {
            NamespacedKey modelKey = NamespacedKey.fromString(modelId);
            if (modelKey == null) return;
            ItemMeta itemMeta = itemStack.getItemMeta();
            if (itemMeta == null) return;
            itemMeta.setItemModel(modelKey);
            itemStack.setItemMeta(itemMeta);
        } catch (Exception | LinkageError ignored) {
            // Keep the vanilla fallback item if custom item models are unavailable.
        }
    }
}
