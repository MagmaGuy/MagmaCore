package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Whether a plugin's root command runs one of the shared Nightbreak subcommands. Each
 * plugin registers those subcommands itself and some skip them, so shared notices and
 * menus check here before naming one.
 */
final class NightbreakSubcommands {
    static final String RECOMMENDED_PLUGINS = "recommendedplugins";
    static final String DOWNLOAD_ALL = "downloadall";
    static final String APPLY_PLUGIN_UPDATE = "applypluginupdate";

    private NightbreakSubcommands() {
    }

    /**
     * @param plugin the plugin owning the root command, or null to resolve it by name
     */
    static boolean isRegistered(JavaPlugin plugin, NightbreakPluginSpec spec, String subcommand) {
        if (spec == null || spec.rootCommand() == null || spec.rootCommand().isBlank()) return false;
        PluginCommand root = plugin != null
                ? plugin.getCommand(spec.rootCommand())
                : Bukkit.getPluginCommand(spec.rootCommand());
        if (root == null) return false;
        if (root.getExecutor() instanceof CommandManager manager) {
            for (AdvancedCommand command : manager.commands) {
                if (command.isEnabled() && command.aliasMatches(subcommand)) return true;
            }
            return false;
        }
        // A plugin with its own executor, such as BetterFood, dispatches these names itself.
        return true;
    }
}
