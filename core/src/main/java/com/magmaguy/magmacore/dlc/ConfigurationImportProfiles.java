package com.magmaguy.magmacore.dlc;

import com.magmaguy.magmacore.util.Logger;
import java.nio.file.Path;

final class ConfigurationImportProfiles {
    private ConfigurationImportProfiles() {
    }

    static Path resolve(ConfigurationImporter importer,
                        String folder,
                        ConfigurationImporter.PluginPlatform platform) {
        if (folder == null) {
            Logger.warn("Directory null for zipped file was not recognized! Was the zipped file packaged correctly?");
            return null;
        }
        // Skip list runs first so entries like pack.meta are never claimed by a platform fallback.
        if (ConfigurationImportRegistry.isSkippedFolder(folder)) {
            return null;
        }
        Path resolvedPath = ConfigurationImportRegistry.resolve(importer, folder, platform);
        if (resolvedPath != null) {
            return resolvedPath;
        }
        // A resolver was registered but intentionally returned null (e.g. customitems
        // for an FMM pack when EliteMobs is not installed). Don't warn about it.
        if (ConfigurationImportRegistry.hasResolver(folder, platform)) {
            return null;
        }
        if ("schematics".equalsIgnoreCase(folder) && platform == ConfigurationImporter.PluginPlatform.ELITEMOBS) {
            Logger.warn("You just tried to import legacy content! Schematic dungeons no longer exist as of EliteMobs 9.0, use BetterStructures shrines instead!");
            return null;
        }
        Logger.warn("Directory " + folder + " for zipped file was not recognized! Was the zipped file packaged correctly?");
        return null;
    }
}
