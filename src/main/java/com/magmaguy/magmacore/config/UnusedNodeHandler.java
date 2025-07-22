package com.magmaguy.magmacore.config;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;

public class UnusedNodeHandler {

    private UnusedNodeHandler() {
    }

    public static Configuration clearNodes(FileConfiguration configuration) {
        //Some configs have no defaults because they're written in weird ways
        if (configuration.getDefaults() == null)
            return configuration;
        for (String actual : configuration.getKeys(false)) {
            boolean keyExists = false;
            for (String defaults : configuration.getDefaults().getKeys(true))
                if (actual.equals(defaults)) {
                    keyExists = true;
                    break;
                }

            if (!keyExists) {
                configuration.set(actual, null);
                Bukkit.getLogger().warning(actual);
                Logger.info("Deleting unused config values.");
            }
        }
        return configuration;
    }

}
