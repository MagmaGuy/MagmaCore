package com.magmaguy.magmacore.command;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

/**
 * Makes commands added to or removed from Bukkit's command map after startup
 * usable by players.
 *
 * <p>Spigot parses the commands players type or click with the vanilla
 * dispatcher, which CraftServer copies from the command map only in
 * {@code syncCommands()}: once at startup, on reloads and after a plugin hot
 * swap. A command registered later is in the command map but players get
 * "Unknown or incomplete command" until something syncs. The console still
 * reaches it through the command map.</p>
 *
 * <p>{@code PluginHotSwapRoutine} keeps its own copy of {@link #syncNow()}: it
 * runs from a detached class loader that cannot load MagmaCore classes.</p>
 */
public final class CommandTreeSync {
    private static int scheduledTaskId = -1;

    private CommandTreeSync() {
    }

    /**
     * Syncs the command tree on the next tick, once for any number of requests
     * made before then. Does nothing while the plugin is disabled: a plugin
     * that is shutting down cannot schedule work, and the server's own
     * reload, shutdown or hot-swap sync covers it.
     */
    public static synchronized void requestSync(Plugin plugin) {
        if (plugin == null || !plugin.isEnabled()) return;
        if (scheduledTaskId != -1 && Bukkit.getScheduler().isQueued(scheduledTaskId)) return;
        try {
            scheduledTaskId = Bukkit.getScheduler().runTask(plugin, () -> {
                synchronized (CommandTreeSync.class) {
                    scheduledTaskId = -1;
                }
                try {
                    syncNow();
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING,
                            "Could not resend the command tree, so commands added since startup may be unknown to players.",
                            exception);
                }
            }).getTaskId();
        } catch (RuntimeException exception) {
            scheduledTaskId = -1;
            plugin.getLogger().log(Level.WARNING, "Could not schedule a command tree resend.", exception);
        }
    }

    /**
     * Rebuilds the vanilla dispatcher from the command map and resends it to
     * every online player. Main thread only.
     */
    public static void syncNow() throws ReflectiveOperationException {
        Object server = Bukkit.getServer();
        server.getClass().getMethod("syncCommands").invoke(server);
    }
}
