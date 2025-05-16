package com.magmaguy.magmacore.util;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.MagmaCore;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

public class VersionChecker {
    @Getter
    private static final boolean SHA1Updated = false;

    private VersionChecker() {
    }

    /**
     * Compares a Minecraft version with the current version on the server. Returns true if the version on the server is older.
     *
     * @param majorVersion Target major version to compare (i.e. 1.>>>17<<<.0)
     * @param minorVersion Target minor version to compare (i.e. 1.17.>>>0<<<)
     * @return Whether the version is under the value to be compared
     */
    public static boolean serverVersionOlderThan(int majorVersion, int minorVersion) {

        String[] splitVersion = Bukkit.getBukkitVersion().split("[.]");

        int actualMajorVersion = Integer.parseInt(splitVersion[1].split("-")[0]);

        int actualMinorVersion = 0;
        if (splitVersion.length > 2)
            actualMinorVersion = Integer.parseInt(splitVersion[2].split("-")[0]);

        if (actualMajorVersion < majorVersion)
            return true;

        if (splitVersion.length > 2)
            return actualMajorVersion == majorVersion && actualMinorVersion < minorVersion;

        return false;
    }

    public static void checkPluginVersion(String resourceID) {
        new BukkitRunnable() {
            @Override
            public void run() {
                String currentVersion = MagmaCore.getInstance().getRequestingPlugin().getDescription().getVersion();
                boolean snapshot = false;
                if (currentVersion.contains("SNAPSHOT")) {
                    snapshot = true;
                    currentVersion = currentVersion.split("-")[0];
                }
                String publicVersion = "";

                try {
                    Logger.info("Latest public release is " + VersionChecker.readStringFromURL("https://api.spigotmc.org/legacy/update.php?resource="+ resourceID));
                    Logger.info("Your version is " + MagmaCore.getInstance().getRequestingPlugin().getDescription().getVersion());
                    publicVersion = VersionChecker.readStringFromURL("https://api.spigotmc.org/legacy/update.php?resource=" + resourceID);
                } catch (IOException e) {
                    Logger.warn("Couldn't check latest version");
                    pluginIsUpToDate = false; // Assume up to date if we can't check
                    return;
                }

                if (Double.parseDouble(currentVersion.split("\\.")[0]) < Double.parseDouble(publicVersion.split("\\.")[0])) {
                    outOfDateHandler();
                    pluginIsUpToDate = false;
                } else if (Double.parseDouble(currentVersion.split("\\.")[0]) == Double.parseDouble(publicVersion.split("\\.")[0])) {
                    if (Double.parseDouble(currentVersion.split("\\.")[1]) < Double.parseDouble(publicVersion.split("\\.")[1])) {
                        outOfDateHandler();
                        pluginIsUpToDate = false;
                    } else if (Double.parseDouble(currentVersion.split("\\.")[1]) == Double.parseDouble(publicVersion.split("\\.")[1])) {
                        if (Double.parseDouble(currentVersion.split("\\.")[2]) < Double.parseDouble(publicVersion.split("\\.")[2])) {
                            outOfDateHandler();
                            pluginIsUpToDate = false;
                        }
                    }
                }

                if (pluginIsUpToDate) {
                    if (!snapshot)
                        Logger.info("You are running the latest version!");
                    else
                        Logger.info("You are running a snapshot version! You can check for updates in the #releases channel on the Nightbreak Discord!");
                }
            }
        }.runTaskAsynchronously(MagmaCore.getInstance().getRequestingPlugin());
    }

    private static String readStringFromURL(String url) throws IOException {
        try (Scanner scanner = new Scanner(new URL(url).openStream(),
                StandardCharsets.UTF_8)) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    private static void outOfDateHandler() {
        Logger.warn("A newer version of this plugin is available for download!");
    }

    private static boolean pluginIsUpToDate = true;

    public static class VersionCheckerEvents implements Listener {
        @EventHandler
        public void onPlayerJoinEvent(PlayerJoinEvent event) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!event.getPlayer().isOnline()) return;
                    if (!pluginIsUpToDate)
                        Logger.sendMessage(event.getPlayer(), ChatColorConverter.convert("&a[FreeMinecraftModels] &cYour version of FreeMinecraftModels is outdated." +
                                " &aYou can download the latest version from &3&n&ohttps://www.spigotmc.org/resources/free-minecraft-models.111660/"));
                }
            }.runTaskLater(MetadataHandler.PLUGIN, 20L * 3);
        }
    }
}