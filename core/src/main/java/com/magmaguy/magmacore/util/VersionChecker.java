package com.magmaguy.magmacore.util;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.nightbreak.NightbreakAccount;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginUpdater;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

public class VersionChecker {
    @Getter
    private static final boolean SHA1Updated = false;
    private static final long UPDATE_REFRESH_INTERVAL_TICKS = 20L * 60L * 60L;
    private static final long INITIAL_UPDATE_REFRESH_DELAY_TICKS = 20L * 60L * 5L;
    private static final long BOOT_STAGGER_MAX_TICKS = 20L * 60L * 2L;
    private static final int SPIGOT_FALLBACK_TIMEOUT_MS = 3000;
    private static final Set<String> SCHEDULED_VERSION_CHECKS = ConcurrentHashMap.newKeySet();
    private static boolean pluginIsUpToDate = true;

    private VersionChecker() {
    }

    /**
     * Compares a Minecraft version with the current version on the server. Returns true if the version on the server is older.
     * Handles both legacy format (1.21.11) and new year.drop format (26.1).
     *
     * @param majorVersion Target major version to compare (e.g. 21 for 1.21.x, or 26 for 26.x)
     * @param minorVersion Target minor version to compare (e.g. 11 for 1.21.11, or 1 for 26.1)
     * @return Whether the version is under the value to be compared
     */
    public static boolean serverVersionOlderThan(int majorVersion, int minorVersion) {

        String[] splitVersion = Bukkit.getBukkitVersion().split("[.]");

        int actualMajorVersion;
        int actualMinorVersion = 0;

        if (splitVersion[0].equals("1")) {
            // Legacy format: 1.MAJOR.MINOR-R0.1-SNAPSHOT (e.g. 1.21.11-R0.1-SNAPSHOT)
            actualMajorVersion = Integer.parseInt(splitVersion[1].split("-")[0]);
            if (splitVersion.length > 2)
                actualMinorVersion = Integer.parseInt(splitVersion[2].split("-")[0]);
        } else {
            // New year.drop format: MAJOR.MINOR-R0.1-SNAPSHOT (e.g. 26.1-R0.1-SNAPSHOT)
            actualMajorVersion = Integer.parseInt(splitVersion[0].split("-")[0]);
            if (splitVersion.length > 1)
                actualMinorVersion = Integer.parseInt(splitVersion[1].split("-")[0]);
        }

        if (actualMajorVersion < majorVersion)
            return true;

        if (actualMajorVersion == majorVersion)
            return actualMinorVersion < minorVersion;

        return false;
    }

    public static void checkPluginVersion(String resourceID) {
        checkPluginVersion(resourceID, VersionCheckerEvents.getDownloadURL());
    }

    public static void checkPluginVersion(String resourceID, String downloadURL) {
        String taskKey = MagmaCore.getInstance().getRequestingPlugin().getName() + ":" + resourceID + ":" + downloadURL;
        if (!SCHEDULED_VERSION_CHECKS.add(taskKey)) return;
        Bukkit.getScheduler().runTaskTimerAsynchronously(MagmaCore.getInstance().getRequestingPlugin(), () -> {
            pluginIsUpToDate = true;
            String rawCurrentVersion = MagmaCore.getInstance().getRequestingPlugin().getDescription().getVersion();
            boolean snapshot = rawCurrentVersion.contains("SNAPSHOT");
            String currentVersion = NightbreakPluginUpdater.cleanVersion(rawCurrentVersion);
            String publicVersion = null;
            String pluginSlug = pluginSlugFromNightbreakUrl(downloadURL);

            if (pluginSlug != null) {
                NightbreakAccount.VersionInfo versionInfo = NightbreakAccount.getPublicPluginVersion(pluginSlug, false);
                if (versionInfo != null && versionInfo.version != null && !versionInfo.version.isBlank()) {
                    publicVersion = versionInfo.version;
                    VersionCheckerEvents.setDownloadURL(downloadPageUrl(pluginSlug));
                    Logger.info("Latest Nightbreak release is " + publicVersion);
                }
            }

            if ((publicVersion == null || publicVersion.isBlank()) && resourceID != null && !resourceID.isBlank()) {
                try {
                    publicVersion = VersionChecker.readStringFromURL("https://api.spigotmc.org/legacy/update.php?resource=" + resourceID).trim();
                    Logger.info("Nightbreak unavailable; latest Spigot release is " + publicVersion);
                } catch (IOException e) {
                    Logger.warn("Couldn't check latest plugin version from Nightbreak or Spigot.");
                    return;
                }
            }

            if (publicVersion == null || publicVersion.isBlank()) {
                Logger.warn("Couldn't check latest plugin version.");
                return;
            }

            Logger.info("Your version is " + rawCurrentVersion);
            if (NightbreakPluginUpdater.compareVersions(publicVersion, currentVersion) > 0) {
                outOfDateHandler();
                pluginIsUpToDate = false;
            }

            if (pluginIsUpToDate) {
                if (!snapshot)
                    Logger.info("You are running the latest version!");
                else
                    Logger.info("You are running a snapshot version! You can check for updates in the #releases channel on the Nightbreak Discord!");
            }
        }, initialRefreshDelayTicks(taskKey), UPDATE_REFRESH_INTERVAL_TICKS);
    }

    private static String readStringFromURL(String url) throws IOException {
        URLConnection connection = new URL(url).openConnection();
        connection.setConnectTimeout(SPIGOT_FALLBACK_TIMEOUT_MS);
        connection.setReadTimeout(SPIGOT_FALLBACK_TIMEOUT_MS);
        try (Scanner scanner = new Scanner(connection.getInputStream(),
                StandardCharsets.UTF_8)) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    private static long initialRefreshDelayTicks(String taskKey) {
        return INITIAL_UPDATE_REFRESH_DELAY_TICKS + Math.floorMod(taskKey.hashCode(), (int) BOOT_STAGGER_MAX_TICKS);
    }

    private static void outOfDateHandler() {
        Logger.warn("A newer version of this plugin is available for download!");
    }

    private static String pluginSlugFromNightbreakUrl(String downloadURL) {
        if (downloadURL == null || downloadURL.isBlank()) return null;
        try {
            URI uri = URI.create(downloadURL);
            String[] parts = uri.getPath().split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                if ("plugin".equals(parts[i]) && !parts[i + 1].isBlank()) {
                    return parts[i + 1];
                }
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    private static String downloadPageUrl(String pluginSlug) {
        return "https://nightbreak.io/plugin/" + pluginSlug + "/download/";
    }

    public static class VersionCheckerEvents implements Listener {
        @Getter
        @Setter
        private static String downloadURL;

        @EventHandler
        public void onPlayerJoinEvent(PlayerJoinEvent event) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!event.getPlayer().isOp()) return;
                    if (!event.getPlayer().isOnline()) return;
                    if (!pluginIsUpToDate)
                        Logger.sendMessage(event.getPlayer(), ChatColorConverter.convert("&a[" + MagmaCore.getInstance().getRequestingPlugin().getName() + "] &cYour version of " + MagmaCore.getInstance().getRequestingPlugin().getName() + " is outdated." +
                                " &aYou can download the latest version from &3&n&o" + downloadURL));
                }
            }.runTaskLater(MagmaCore.getInstance().getRequestingPlugin(), 20L * 3);
        }
    }
}
