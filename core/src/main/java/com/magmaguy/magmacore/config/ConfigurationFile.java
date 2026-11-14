package com.magmaguy.magmacore.config;

import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class ConfigurationFile {
    /**
     * The most recently loaded instance for each file. Shared writers (for example the Nightbreak
     * update toggle) write through this live model so the owning plugin's later whole-model saves
     * keep their change instead of overwriting it with a stale copy.
     */
    private static final Map<Path, ConfigurationFile> LIVE_FILES = new ConcurrentHashMap<>();

    protected final File file;
    @Getter
    protected FileConfiguration fileConfiguration;

    protected ConfigurationFile(String filename) {
        this(ConfigurationEngine.fileCreator(filename));
    }

    /** Loads the same configuration lifecycle without requiring a plugin-owned path. */
    protected ConfigurationFile(File file) {
        this.file = ConfigurationEngine.fileCreator(java.util.Objects.requireNonNull(file, "file"));
        fileConfiguration = java.util.Objects.requireNonNull(
                ConfigurationEngine.fileConfigurationCreator(file),
                "Configuration could not be loaded: " + file);
        initializeValues();
        saveDefaults();
        LIVE_FILES.put(liveKey(this.file), this);
    }

    public abstract void initializeValues();

    public void saveDefaults() {
        ConfigurationEngine.fileSaverOnlyDefaults(fileConfiguration, file);
    }

    /**
     * @return the live model of the most recently loaded {@link ConfigurationFile} for this file,
     * or null when none has loaded it. Callers that change it must save it with
     * {@link ConfigurationEngine#writeValue}, which the owning plugin's own writers also use.
     */
    public static FileConfiguration findLiveConfiguration(File file) {
        if (file == null) return null;
        ConfigurationFile live = LIVE_FILES.get(liveKey(file));
        return live == null ? null : live.fileConfiguration;
    }

    private static Path liveKey(File file) {
        return file.toPath().toAbsolutePath().normalize();
    }

}
