package com.magmaguy.magmacore.config;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.Set;

public class UnusedNodeHandler {

    private UnusedNodeHandler() {
    }

    public static Configuration clearNodes(FileConfiguration configuration) {
        //Some configs have no defaults because they're written in weird ways
        if (configuration.getDefaults() == null)
            return configuration;
        // Build the deep default key set once instead of once per top-level key.
        Set<String> defaultKeys = new HashSet<>(configuration.getDefaults().getKeys(true));
        for (String actual : configuration.getKeys(false)) {
            if (!defaultKeys.contains(actual)) {
                configuration.set(actual, null);
                Logger.info("Deleting unused config values.");
            }
        }
        return configuration;
    }

}
