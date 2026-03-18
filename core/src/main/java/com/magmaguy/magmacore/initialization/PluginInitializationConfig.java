package com.magmaguy.magmacore.initialization;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public record PluginInitializationConfig(String displayName,
                                         String adminPermission,
                                         int totalSteps,
                                         List<String> dependencies) {
    public PluginInitializationConfig(String displayName,
                                      String adminPermission,
                                      int totalSteps) {
        this(displayName, adminPermission, totalSteps, List.of());
    }

    public List<String> resolveDependencies(JavaPlugin plugin) {
        if (dependencies != null && !dependencies.isEmpty()) {
            return dependencies;
        }
        return plugin.getDescription().getSoftDepend();
    }
}
