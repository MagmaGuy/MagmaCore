package com.magmaguy.magmacore.nightbreak;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NightbreakPluginStateRegistry {
    private static final Map<String, CommandSender> pendingReloadSenders = new ConcurrentHashMap<>();
    private static final Map<String, AtomicBoolean> bulkOperationGuards = new ConcurrentHashMap<>();

    private NightbreakPluginStateRegistry() {
    }

    public static void setPendingReloadSender(JavaPlugin plugin, CommandSender sender) {
        if (plugin == null) return;
        if (sender == null) {
            pendingReloadSenders.remove(plugin.getName());
        } else {
            pendingReloadSenders.put(plugin.getName(), sender);
        }
    }

    public static CommandSender consumePendingReloadSender(JavaPlugin plugin) {
        if (plugin == null) return null;
        return pendingReloadSenders.remove(plugin.getName());
    }

    public static void clearPendingReloadSender(JavaPlugin plugin) {
        if (plugin == null) return;
        pendingReloadSenders.remove(plugin.getName());
    }

    public static AtomicBoolean getBulkOperationGuard(JavaPlugin plugin) {
        return bulkOperationGuards.computeIfAbsent(plugin.getName(), ignored -> new AtomicBoolean(false));
    }

    public static void clear(JavaPlugin plugin) {
        if (plugin == null) return;
        pendingReloadSenders.remove(plugin.getName());
        bulkOperationGuards.remove(plugin.getName());
    }
}
