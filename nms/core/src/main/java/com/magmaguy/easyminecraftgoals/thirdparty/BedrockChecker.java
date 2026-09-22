package com.magmaguy.easyminecraftgoals.thirdparty;

import com.magmaguy.magmacore.MagmaCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.regex.Pattern;

/**
 * Shared Bukkit-side Bedrock detection for presentation, packets and pack delivery.
 * Floodgate's synthetic UUID survives proxy forwarding without a local Floodgate
 * plugin. The existing dotted-name heuristic is also retained, but a numeric suffix
 * is not required by Floodgate and is not the primary identity signal.
 * Local APIs additionally identify linked accounts with ordinary Java UUIDs.
 * These presentation heuristics must not be used for authentication or permissions.
 */
public class BedrockChecker {
    private BedrockChecker() {
    }

    private static final Pattern BEDROCK_NAME_PATTERN = Pattern.compile("^\\..*\\d{4}$");
    private static volatile Providers tracker;

    public static boolean isBedrock(Player player) {
        if (player == null) return false;
        if (player.getUniqueId().getMostSignificantBits() == 0L) return true;
        String name = player.getName();
        if (name != null && BEDROCK_NAME_PATTERN.matcher(name).matches()) return true;
        Available available = available();
        if (available.floodgate() != null && available.floodgate().isEnabled() && Floodgate.isBedrock(player)) return true;
        return available.geyser() != null && available.geyser().isEnabled() && Geyser.isBedrock(player);
    }

    /** Local API availability, not proof that a proxy cannot supply Bedrock viewers. */
    public static boolean isBedrockSupportPresent() {
        Available available = available();
        return available.floodgate() != null && available.floodgate().isEnabled()
                || available.geyser() != null && available.geyser().isEnabled();
    }

    private static Available available() {
        PluginManager manager = Bukkit.getPluginManager();
        Providers current = tracker;
        if (current != null && current.manager == manager && current.owner.isEnabled()) return current.available;
        MagmaCore core = MagmaCore.getInstance();
        Plugin owner = core == null ? null : core.getRequestingPlugin();
        // Standalone callers have no lifecycle owner yet. Do not retain a negative result
        // without a registered listener that can invalidate it.
        if (owner == null || !owner.isEnabled() || !Bukkit.isPrimaryThread()) return discover(manager, null);
        synchronized (BedrockChecker.class) {
            current = tracker;
            if (current != null && current.manager == manager && current.owner.isEnabled()) return current.available;
            if (current != null) HandlerList.unregisterAll(current);
            current = new Providers(manager, owner);
            manager.registerEvents(current, owner);
            tracker = current;
            return current.available;
        }
    }

    private static Available discover(PluginManager manager, Plugin excluded) {
        Plugin floodgate = null, geyser = null;
        for (Plugin plugin : manager.getPlugins()) {
            if (plugin == excluded || !plugin.isEnabled()) continue;
            if (plugin.getName().equalsIgnoreCase("floodgate")) floodgate = plugin;
            else if (isGeyser(plugin.getName())) geyser = plugin;
        }
        return new Available(floodgate, geyser);
    }

    private static boolean isGeyser(String name) {
        return name.equalsIgnoreCase("Geyser-Spigot") || name.equalsIgnoreCase("Geyser-Bukkit")
                || name.equalsIgnoreCase("Geyser");
    }

    private record Available(Plugin floodgate, Plugin geyser) { }

    private static final class Providers implements Listener {
        private final PluginManager manager;
        private final Plugin owner;
        private volatile Available available;

        private Providers(PluginManager manager, Plugin owner) {
            this.manager = manager;
            this.owner = owner;
            available = discover(manager, null);
        }

        @EventHandler
        public void onEnable(PluginEnableEvent event) {
            if (relevant(event.getPlugin())) available = discover(manager, null);
        }

        @EventHandler
        public void onDisable(PluginDisableEvent event) {
            if (event.getPlugin() == owner) {
                if (tracker == this) tracker = null;
                HandlerList.unregisterAll(this);
            } else if (relevant(event.getPlugin())) {
                // Bukkit may fire this event before changing Plugin.isEnabled().
                available = discover(manager, event.getPlugin());
            }
        }

        private boolean relevant(Plugin plugin) {
            return plugin.getName().equalsIgnoreCase("floodgate") || isGeyser(plugin.getName());
        }
    }
}
