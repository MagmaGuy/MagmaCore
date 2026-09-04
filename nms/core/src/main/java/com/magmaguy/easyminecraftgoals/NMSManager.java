package com.magmaguy.easyminecraftgoals;

import com.magmaguy.easyminecraftgoals.internal.PacketEntityTracker;
import com.magmaguy.easyminecraftgoals.visuals.terrain.PacketTerrainImpactRenderer;
import com.magmaguy.magmacore.visuals.terrain.TerrainImpactService;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.logging.Level;

public class NMSManager {
    private static final String PACKAGE = "com.magmaguy.easyminecraftgoals.";
    /**
     * Oldest Minecraft release this build ships an NMS adapter for. The pre-1.21.4 adapter
     * modules have been deleted; recover them from git history if they are ever needed again.
     */
    private static final String MINIMUM_SUPPORTED_MINECRAFT_VERSION = "1.21.4";
    /**
     * Internals names that actually have a bundled adapter. Kept in sync with the
     * modules included in settings.gradle.kts.
     */
    private static final Set<String> SUPPORTED_INTERNALS = Set.of(
            "v1_21_R3", "v1_21_R4", "v1_21_R5", "v1_21_R6", "v1_21_R7", "v26");
    public static Plugin pluginProvider;
    private static NMSAdapter adapter;
    @Getter
    private static boolean isEnabled = false;

    public static void initializeAdapter(Plugin plugin) {
        pluginProvider = plugin;
//        plugin.getLogger().info(Bukkit.getServer().getClass().getPackage().getName());
        String version = getServerVersion();
        if (version == null) {
            // getServerVersion() has already logged exactly why (unsupported or unparsable).
            return;
        }

        try {
//            plugin.getLogger().info("Format: " + PACKAGE + version + ".NMSAdapter");
            String versionName;
            // For R7, Paper hard forked and requires separate adapters
            if ("v1_21_R7".equals(version)) {
                if (isPaper()) versionName = PACKAGE + "v1_21_R7_paper" + ".NMSAdapter";
                else versionName = PACKAGE + "v1_21_R7_spigot" + ".NMSAdapter";
            }
            // v26+ is unified - no Paper/Spigot split needed (fully unobfuscated)
            else versionName = PACKAGE + version + ".NMSAdapter";
//            plugin.getLogger().info("Loading class: " + versionName);
            adapter = (NMSAdapter) Class.forName(versionName).getDeclaredConstructor().newInstance();
            plugin.getLogger().log(Level.INFO, "Supported server version detected: {0}", version);
            isEnabled = true;

            // Initialize the packet entity tracker for automatic visibility management
            PacketEntityTracker.getInstance().initialize(plugin);

            // Initialize the packet interaction listener for handling clicks on packet entities
            adapter.initializePacketInteractionListener(plugin);

            // Install the shared packet-only terrain visual. This is deliberately best-effort:
            // an unavailable visual must not disable unrelated NMS features.
            try {
                TerrainImpactService.install(new PacketTerrainImpactRenderer(plugin, adapter));
            } catch (RuntimeException terrainImpactFailure) {
                plugin.getLogger().log(Level.WARNING,
                        "Packet terrain impacts are unavailable: {0}",
                        terrainImpactFailure.getMessage());
            }
        } catch (ClassNotFoundException e) {
            plugin.getLogger().log(Level.SEVERE, "Class not found: {0}", e.getMessage());
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().log(Level.SEVERE, "Error instantiating class: {0}", e.getMessage());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Unexpected error: {0}", e.getMessage());
        } finally {
            if (!isEnabled) {
                plugin.getLogger().log(Level.SEVERE, "Server version \"{0}\" is unsupported! Please check for updates!", version);
                //todo: remains to be seen Bukkit.getPluginManager().disablePlugin(plugin);
            }
        }
    }

    private static boolean isPaper() {
        try {
            Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }


    public static NMSAdapter getAdapter() {
        return adapter;
    }

    /**
     * Shuts down NMS components. Should be called when the plugin using EasyMinecraftGoals is disabled.
     */
    public static void shutdown() {
        // Terrain displays must be removed while the adapter is still available to send packets.
        TerrainImpactService.shutdown();
        if (adapter != null) {
            adapter.shutdownMindHost();
            adapter.shutdownPacketInteractionListener();
        }
        PacketEntityTracker.getInstance().shutdown();
        isEnabled = false;
        adapter = null;
    }

    private static String getServerVersion() {
        String packageName = Bukkit.getServer().getClass().getPackage().getName();
        String versionString = Bukkit.getServer().getVersion();
        if (packageName.contains("_R")) {
            String legacyInternals = packageName.split("\\.")[3];
            if (SUPPORTED_INTERNALS.contains(legacyInternals)) return legacyInternals;
            // CraftBukkit stopped relocating its package after 1.20.4, so a versioned
            // package here always means a server below the supported floor.
            logUnsupportedVersion(versionString);
            return null;
        }

        if (!versionString.contains("-")) {
            pluginProvider.getLogger().warning("Incompatible Minecraft version detected! [1] Package: " + packageName + " version: " + versionString + " ! Report this to the developer.");
            return null;
        }

        try {
            String justVersion = versionString.split("-")[0];
            String[] parts = justVersion.split("\\.");

            int major;
            int minor;

            if (parts[0].equals("1")) {
                // Legacy format: 1.MAJOR.MINOR (e.g. 1.21.11)
                major = Integer.parseInt(parts[1]);
                try {
                    minor = Integer.parseInt(parts[2]);
                } catch (Exception e) {
                    minor = 0;
                }
            } else {
                // New year.drop format: MAJOR.MINOR (e.g. 26.1)
                major = Integer.parseInt(parts[0]);
                try {
                    minor = Integer.parseInt(parts[1]);
                } catch (Exception e) {
                    minor = 0;
                }
            }
            return getInternalsFromRevision(major, minor);
        } catch (Exception e) {
            pluginProvider.getLogger().warning("Incompatible Minecraft version detected! [2] Package: " + packageName + " version: " + versionString + " ! Report this to the developer.");
            return null;
        }
    }

    private static String getInternalsFromRevision(int major, int minor) {
        // Anything below 1.21.4 is intentionally unsupported - those adapters are no longer built.
        if (major == 21) {
            String versionString = "v1_21_";
            if (minor == 4)
                return versionString + "R3";
            if (minor == 5)
                return versionString + "R4";
            if (minor == 6 || minor == 7 || minor == 8)
                return versionString + "R5";
            if (minor == 9 || minor == 10)
                return versionString + "R6";
            if (minor == 11)
                return versionString + "R7";
        } else if (major >= 26) {
            // MC 26.1+ is fully unobfuscated - uses dedicated v26 adapter
            return "v26";
        }
        logUnsupportedVersion(Bukkit.getServer().getVersion());
        return null;
    }

    private static void logUnsupportedVersion(String versionString) {
        pluginProvider.getLogger().log(Level.SEVERE,
                "Unsupported Minecraft version: {0}. This build requires Minecraft {1} or newer; "
                        + "NMS features (packet entities, hitboxes, custom pathfinding, scoreboard number hiding) "
                        + "will stay disabled. Update your server or use an older release of this plugin.",
                new Object[]{versionString, MINIMUM_SUPPORTED_MINECRAFT_VERSION});
    }
}
