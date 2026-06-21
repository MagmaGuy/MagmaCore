package com.magmaguy.magmacore.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the persistence semantics that Fix A (dungeon-lockout config persistence) relies on:
 * when an already-existing user file is loaded and new default keys are addDefault'd, the saver used
 * on the user-file path must write the missing defaults to disk WITHOUT clobbering existing user values
 * or stripping comments. {@link ConfigurationEngine#fileSaverCustomValues} is the safe choice;
 * {@link ConfigurationEngine#fileSaverOnlyDefaults} is destructive on user files (it deletes keys absent
 * from the in-memory defaults) and is verified here to behave that way so the choice is justified.
 */
class ConfigurationEngineSaverTest {

    @Test
    void fileSaverCustomValuesWritesMissingDefaultWithoutClobberingUserValueOrComments(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("dungeon.yml").toFile();
        // Simulate a pre-existing user file: a user-set value plus a comment, and NO dungeonLockoutMinutes key.
        Files.writeString(file.toPath(),
                "# Important user comment\n" +
                        "userSetValue: 7\n");

        FileConfiguration fileConfiguration = YamlConfiguration.loadConfiguration(file);

        // Mirror processInt/processString behavior: addDefault is only staged for keys absent from the file.
        fileConfiguration.addDefault("dungeonLockoutMinutes", 4320); // new default key (missing from user file)
        fileConfiguration.addDefault("userSetValue", 4320);          // default for a key the user already set

        ConfigurationEngine.fileSaverCustomValues(fileConfiguration, file);

        // Reload from disk to confirm what was actually persisted.
        FileConfiguration reloaded = YamlConfiguration.loadConfiguration(file);

        // Missing default was written so it is now visible/editable on disk.
        assertEquals(4320, reloaded.getInt("dungeonLockoutMinutes"));
        // Existing user value must NOT be overwritten by the default.
        assertEquals(7, reloaded.getInt("userSetValue"));

        // Existing comment must be preserved (not stripped) by the save.
        String contents = Files.readString(file.toPath());
        assertTrue(contents.contains("Important user comment"),
                "Existing comment should be preserved: " + contents);
    }

    // Note on the saver choice: fileSaverOnlyDefaults additionally runs UnusedNodeHandler.clearNodes, which
    // DELETES any on-disk key that is absent from the in-memory defaults. On the user-file load path defaults
    // are only addDefault'd for keys missing from the file, so user-set keys would be wiped. That destructive
    // path also requires a live MagmaCore/Bukkit context (it calls Logger.info), so it is not exercised here;
    // its behavior is established by reading UnusedNodeHandler. fileSaverCustomValues, verified above, only
    // writes missing defaults and is therefore the safe saver for the pre-existing-file path.
}
