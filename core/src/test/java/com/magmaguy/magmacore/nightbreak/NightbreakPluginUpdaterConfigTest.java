package com.magmaguy.magmacore.nightbreak;

import org.bukkit.Server;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the reload-safety of {@link NightbreakPluginUpdater#ensureAutoDownloadConfigDefault}.
 * <p>
 * Plugin "reloads" call {@code onDisable()/onEnable()} on the same JavaPlugin instance, and Bukkit
 * caches {@code getConfig()} from its first call. The old implementation saved that cached snapshot
 * on every enable, so any config.yml edit made after boot was clobbered back to boot-time state on
 * the next reload (the ResourcePackManager / ResurrectionChest "config resets on reload" defect).
 * The method must re-read from disk and only write when the auto-download key is missing.
 * <p>
 * Uses spigot-api's protected JavaPlugin test constructor (no MockBukkit/Paper on the classpath —
 * these plugins target Spigot) so the real JavaPlugin config-caching semantics are exercised.
 */
class NightbreakPluginUpdaterConfigTest {

    private JavaPlugin plugin;
    private File configFile;

    private static final class TestPlugin extends JavaPlugin {
        private TestPlugin(JavaPluginLoader loader, PluginDescriptionFile description, File dataFolder, File file) {
            super(loader, description, dataFolder, file);
        }
    }

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Logger serverLogger = Logger.getLogger("NightbreakPluginUpdaterConfigTest");
        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLogger" -> serverLogger;
                    case "getName" -> "TestServer";
                    case "getVersion", "getBukkitVersion" -> "test";
                    default -> throw new UnsupportedOperationException(
                            "Server." + method.getName() + " is not stubbed for this test");
                });
        File dataFolder = tempDir.resolve("plugin-data").toFile();
        dataFolder.mkdirs();
        plugin = new TestPlugin(
                new JavaPluginLoader(server),
                new PluginDescriptionFile("TestPlugin", "1.0", "com.example.TestPlugin"),
                dataFolder,
                tempDir.resolve("TestPlugin.jar").toFile());
        configFile = new File(dataFolder, "config.yml");
    }

    @Test
    void postBootDiskEditsSurviveReload() throws Exception {
        // Boot: key already present, user value at its boot-time state.
        Files.writeString(configFile.toPath(),
                "userSetting: original\n"
                        + "nightbreak:\n"
                        + "  autoDownloadPluginUpdates: false\n");
        NightbreakPluginUpdater.ensureAutoDownloadConfigDefault(plugin);
        // JavaPlugin has now cached this config state, like a real boot does.
        plugin.getConfig();

        // Admin edits config.yml while the server is running...
        Files.writeString(configFile.toPath(),
                "userSetting: edited\n"
                        + "nightbreak:\n"
                        + "  autoDownloadPluginUpdates: true\n");

        // ...and reloads the plugin, which re-runs the bootstrap success callback.
        NightbreakPluginUpdater.ensureAutoDownloadConfigDefault(plugin);

        String onDisk = Files.readString(configFile.toPath());
        assertTrue(onDisk.contains("edited"),
                "post-boot config edits must survive a plugin reload, got:\n" + onDisk);
        assertFalse(onDisk.contains("original"),
                "boot-time snapshot must not be written back over user edits, got:\n" + onDisk);
        assertTrue(NightbreakPluginUpdater.isAutoDownloadPluginUpdatesEnabled(plugin),
                "the edited auto-download value must take effect after reload");
    }

    @Test
    void missingKeyIsWrittenWithoutTouchingOtherValues() throws Exception {
        Files.writeString(configFile.toPath(), "userSetting: kept\n");

        NightbreakPluginUpdater.ensureAutoDownloadConfigDefault(plugin);

        String onDisk = Files.readString(configFile.toPath());
        assertTrue(onDisk.contains("autoDownloadPluginUpdates"),
                "missing auto-download key must be written so admins can discover it, got:\n" + onDisk);
        assertTrue(onDisk.contains("kept"),
                "existing keys must be preserved when the default is added, got:\n" + onDisk);
        assertFalse(NightbreakPluginUpdater.isAutoDownloadPluginUpdatesEnabled(plugin),
                "the written default must be false");
    }
}
