package com.magmaguy.magmacore.util;

import com.magmaguy.easyminecraftgoals.ItemComponentCodec;
import com.magmaguy.easyminecraftgoals.NMSManager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.logging.Level;

/**
 * Reads an item's data components as the JSON the server's own codecs write, on every
 * server version MagmaCore has an adapter for.
 *
 * <p>This loads only the small per-version codec class. It never initializes the full NMS
 * adapter, so a plugin can use it without MagmaCore installing packet listeners or entity
 * trackers. When the codec cannot load or a stack cannot be encoded, the result is empty and
 * the cause is logged once.</p>
 */
public final class ItemComponents {
    private static final Object LOCK = new Object();
    private static volatile ItemComponentCodec codec;
    private static volatile boolean resolved;
    private static volatile boolean encodeFailureLogged;

    private ItemComponents() {
    }

    /**
     * @param plugin    the calling plugin, for its logger
     * @param item      the stack to read; call on the server thread
     * @param patchOnly {@code true} for the stack's changes against its item's prototype, with
     *                  removed prototype components as {@code "!namespace:id": {}};
     *                  {@code false} for every component the stack has
     * @return the components as a JSON object string, or empty when this server cannot encode
     *         them
     */
    public static Optional<String> json(Plugin plugin, ItemStack item, boolean patchOnly) {
        if (item == null) return Optional.empty();
        ItemComponentCodec active = codec(plugin);
        if (active == null) return Optional.empty();
        try {
            return Optional.ofNullable(active.componentsJson(item, patchOnly));
        } catch (RuntimeException | LinkageError failure) {
            if (!encodeFailureLogged) {
                encodeFailureLogged = true;
                plugin.getLogger().log(Level.WARNING,
                        "Could not read item components on this server version: {0}", failure.toString());
            }
            return Optional.empty();
        }
    }

    /** True when this server version has a working component codec. */
    public static boolean isAvailable(Plugin plugin) {
        return codec(plugin) != null;
    }

    private static ItemComponentCodec codec(Plugin plugin) {
        if (resolved) return codec;
        synchronized (LOCK) {
            if (resolved) return codec;
            try {
                String adapterPackage = NMSManager.adapterPackage(plugin);
                if (adapterPackage != null) {
                    codec = (ItemComponentCodec) Class.forName(adapterPackage + ".ItemComponentCodecImpl")
                            .getDeclaredConstructor().newInstance();
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                plugin.getLogger().log(Level.WARNING,
                        "Item components cannot be read on this server version: {0}", unavailable.toString());
                codec = null;
            }
            resolved = true;
            return codec;
        }
    }
}
