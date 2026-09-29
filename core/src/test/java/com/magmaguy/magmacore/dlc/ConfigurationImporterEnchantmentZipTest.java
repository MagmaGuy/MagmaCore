package com.magmaguy.magmacore.dlc;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ConfigurationImporterEnchantmentZipTest {

    private static final String ENCHANTMENT = "isEnabled: true\nname: Shrine Ward\nmaxLevel: 3\n"
            + "validSlots: [MAINHAND]\nstacking: sum_equipped_levels\nscript: shrine_ward.lua\n";
    private static final String SCRIPT = "return { api_version = 1, on_critical_chance = function(context) "
            + "return context.enchantment.level / 10 end }\n";
    private static final String CUSTOM_ITEM = "material: DIAMOND_SWORD\nname: Shrine Sword\n";
    private static final String CONTENT_PACKAGE = "name: Enchanted Shrine\n";

    @TempDir
    Path temporaryDirectory;

    @Test
    void importsBetterStructuresZipWithEliteMobsEnchantmentsAndLocalScripts() throws Exception {
        Path archive = writeArchive(packEntries());

        ConfigurationImporter importer = runImporter();

        assertEquals(ENCHANTMENT, Files.readString(plugins().resolve("EliteMobs/enchantments/shrine_ward.yml")));
        assertEquals(SCRIPT, Files.readString(plugins().resolve("EliteMobs/enchantments/shrine_ward.lua")));
        assertEquals(CUSTOM_ITEM, Files.readString(plugins().resolve("EliteMobs/customitems/shrine_sword.yml")));
        assertEquals(CONTENT_PACKAGE, Files.readString(plugins().resolve("BetterStructures/content_packages/enchanted_shrine.yml")));
        assertFalse(Files.exists(plugins().resolve("BetterStructures/enchantments")));
        assertTrue(importer.isEliteMobsContentImported(), "the owner must reload EliteMobs after installing its definitions");
        assertFalse(Files.exists(archive), "only a completely installed ZIP may be consumed");
        assertImportDirectoryContains(List.of());
    }

    @Test
    void unknownTopLevelFolderRetainsTheWholeZipWithoutInstallingRecognizedEntries() throws Exception {
        Map<String, String> entries = packEntries();
        entries.put("unrecognized/rejected.yml", "name: Unsupported\n");
        Path archive = writeArchive(entries);
        byte[] originalArchive = Files.readAllBytes(archive);

        ConfigurationImporter importer = runImporter();

        assertArrayEquals(originalArchive, Files.readAllBytes(archive), "a rejected archive must remain intact for retry");
        assertFalse(Files.exists(plugins().resolve("EliteMobs")), "rejection must happen before enchantments or custom items are installed");
        assertFalse(Files.exists(plugins().resolve("BetterStructures/content_packages")));
        assertFalse(Files.exists(plugins().resolve("BetterStructures/enchantments")));
        assertFalse(importer.isEliteMobsContentImported());
        assertImportDirectoryContains(List.of(archive.getFileName().toString()));
    }

    private ConfigurationImporter runImporter() {
        JavaPlugin owner = mock(JavaPlugin.class);
        when(owner.getName()).thenReturn("BetterStructures");
        when(owner.getDataFolder()).thenReturn(plugins().resolve("BetterStructures").toFile());
        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.getPlugins()).thenReturn(new Plugin[]{owner});
        ServicesManager servicesManager = mock(ServicesManager.class);
        when(servicesManager.getRegistrations(Runnable.class)).thenReturn(List.of());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            bukkit.when(Bukkit::getServicesManager).thenReturn(servicesManager);
            bukkit.when(Bukkit::getLogger).thenReturn(java.util.logging.Logger.getLogger(getClass().getName()));
            return new ConfigurationImporter(owner);
        }
    }

    private Map<String, String> packEntries() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("pack.meta", "BetterStructures\n");
        entries.put("enchantments/", "");
        entries.put("enchantments/shrine_ward.yml", ENCHANTMENT);
        entries.put("enchantments/shrine_ward.lua", SCRIPT);
        entries.put("customitems/shrine_sword.yml", CUSTOM_ITEM);
        entries.put("content_packages/enchanted_shrine.yml", CONTENT_PACKAGE);
        return entries;
    }

    private Path writeArchive(Map<String, String> entries) throws IOException {
        Path imports = plugins().resolve("BetterStructures/imports");
        Files.createDirectories(imports);
        Path archive = imports.resolve("enchanted_shrine.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }

    private void assertImportDirectoryContains(List<String> expected) throws IOException {
        try (var files = Files.list(plugins().resolve("BetterStructures/imports"))) {
            assertEquals(expected, files.map(path -> path.getFileName().toString()).sorted().toList(),
                    "extraction staging and transaction directories must be cleaned");
        }
    }

    private Path plugins() {
        return temporaryDirectory.resolve("plugins");
    }
}
