package com.magmaguy.magmacore.initialization;

import org.bukkit.plugin.java.JavaPlugin;

public class PluginInitializationContext {
    private final JavaPlugin plugin;
    private final PluginInitializationConfig config;

    PluginInitializationContext(JavaPlugin plugin, PluginInitializationConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public PluginInitializationConfig getConfig() {
        return config;
    }

    public void step(String description) {
        PluginInitializationProgressBar.step(plugin, description);
    }

    public void status(String description) {
        PluginInitializationProgressBar.status(plugin, description);
    }

    public boolean isShutdownRequested() {
        return PluginInitializationManager.isShutdownRequested(plugin);
    }
}
