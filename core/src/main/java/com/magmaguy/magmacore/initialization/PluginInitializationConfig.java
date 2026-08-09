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
        //null, not List.of(): null means "infer from plugin.yml softdepend", while an explicitly
        //supplied empty list means "this plugin genuinely waits for nobody". Collapsing the two
        //would make it impossible to opt out of the softdepend gate.
        this(displayName, adminPermission, totalSteps, null);
    }

    public List<String> resolveDependencies(JavaPlugin plugin) {
        if (dependencies != null) {
            return dependencies;
        }
        return plugin.getDescription().getSoftDepend();
    }
}
