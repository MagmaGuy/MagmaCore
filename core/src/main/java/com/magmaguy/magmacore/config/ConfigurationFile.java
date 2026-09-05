package com.magmaguy.magmacore.config;

import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public abstract class ConfigurationFile {
    protected final File file;
    @Getter
    protected FileConfiguration fileConfiguration;

    protected ConfigurationFile(String filename) {
        this(ConfigurationEngine.fileCreator(filename));
    }

    /** Loads the same configuration lifecycle without requiring a plugin-owned path. */
    protected ConfigurationFile(File file) {
        this.file = ConfigurationEngine.fileCreator(java.util.Objects.requireNonNull(file, "file"));
        fileConfiguration = YamlConfiguration.loadConfiguration(file);
        initializeValues();
        saveDefaults();
    }

    public abstract void initializeValues();

    public void saveDefaults() {
        ConfigurationEngine.fileSaverOnlyDefaults(fileConfiguration, file);
    }

}
