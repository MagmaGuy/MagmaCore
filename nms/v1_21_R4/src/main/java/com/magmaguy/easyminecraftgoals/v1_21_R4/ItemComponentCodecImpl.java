package com.magmaguy.easyminecraftgoals.v1_21_R4;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/**
 * Encodes an item's data components with the server's own codecs, for
 * {@link com.magmaguy.magmacore.util.ItemComponents}.
 */
public final class ItemComponentCodecImpl implements com.magmaguy.easyminecraftgoals.ItemComponentCodec {

    @Override
    public String componentsJson(org.bukkit.inventory.ItemStack item, boolean patchOnly) {
        ItemStack nativeItem = org.bukkit.craftbukkit.v1_21_R4.inventory.CraftItemStack.asNMSCopy(item);
        return encode(nativeItem, MinecraftServer.getServer().registryAccess(), patchOnly);
    }

    /** Visible for tests, which supply the built-in registries instead of a running server. */
    static String encode(ItemStack nativeItem, HolderLookup.Provider registries, boolean patchOnly) {
        DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        JsonObject out = new JsonObject();
        if (patchOnly) {
            var split = nativeItem.getComponentsPatch().split();
            for (var typed : split.added()) {
                var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(typed.type());
                if (key == null) continue;
                encodeOne(out, key.toString(), typed.type(), typed.value(), ops);
            }
            for (var removed : split.removed()) {
                var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(removed);
                if (key != null) out.add("!" + key, new JsonObject());
            }
        } else {
            for (var typed : nativeItem.getComponents()) {
                var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(typed.type());
                if (key == null) continue;
                encodeOne(out, key.toString(), typed.type(), typed.value(), ops);
            }
        }
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T> void encodeOne(JsonObject out, String key, DataComponentType<T> type, Object value,
                                      DynamicOps<JsonElement> ops) {
        var codec = type.codec();
        // Transient components (no codec) are never sent to clients, so they never matter here.
        if (codec == null) return;
        codec.encodeStart(ops, (T) value).result().ifPresent(json -> out.add(key, json));
    }
}
