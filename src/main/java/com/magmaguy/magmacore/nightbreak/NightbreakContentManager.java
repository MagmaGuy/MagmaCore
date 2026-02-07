package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Utility class for managing Nightbreak content downloads and access checks.
 * Provides async operations and caching for efficient content management.
 */
public class NightbreakContentManager {

    @Getter
    private static final Map<String, NightbreakAccount.AccessInfo> accessCache = new HashMap<>();
    @Getter
    private static final Map<String, NightbreakAccount.VersionInfo> versionCache = new HashMap<>();

    private static long lastCacheRefresh = 0;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

    /**
     * Checks if the cache is stale and needs refreshing.
     */
    public static boolean isCacheStale() {
        return System.currentTimeMillis() - lastCacheRefresh > CACHE_TTL_MS;
    }

    /**
     * Clears all caches.
     */
    public static void clearCache() {
        accessCache.clear();
        versionCache.clear();
        lastCacheRefresh = 0;
    }

    /**
     * Refreshes the version cache by fetching all versions from Nightbreak.
     * Should be called async.
     */
    public static void refreshVersionCache() {
        if (!NightbreakAccount.hasToken()) return;

        Map<String, NightbreakAccount.VersionInfo> versions = NightbreakAccount.getInstance().getAllVersions();
        if (!versions.isEmpty()) {
            versionCache.clear();
            versionCache.putAll(versions);
            lastCacheRefresh = System.currentTimeMillis();
            Logger.info("Refreshed Nightbreak version cache with " + versions.size() + " entries");
        }
    }

    /**
     * Checks access for a content slug asynchronously.
     *
     * @param slug The content slug
     * @param callback Called with the AccessInfo result (may be null on error)
     */
    public static void checkAccessAsync(String slug, Consumer<NightbreakAccount.AccessInfo> callback) {
        if (!NightbreakAccount.hasToken()) {
            callback.accept(null);
            return;
        }

        // Check cache first
        if (accessCache.containsKey(slug) && !isCacheStale()) {
            callback.accept(accessCache.get(slug));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(MagmaCore.getInstance().getRequestingPlugin(), () -> {
            NightbreakAccount.AccessInfo info = NightbreakAccount.getInstance().checkAccess(slug);
            if (info != null) {
                accessCache.put(slug, info);
            }
            // Return to main thread for callback
            Bukkit.getScheduler().runTask(MagmaCore.getInstance().getRequestingPlugin(), () -> {
                callback.accept(info);
            });
        });
    }

    /**
     * Downloads content asynchronously with progress updates.
     *
     * @param slug The content slug to download
     * @param destinationFolder The folder to save the downloaded file
     * @param player The player to send progress messages to (can be null)
     * @param onComplete Called when download completes (true = success)
     */
    public static void downloadAsync(String slug, File destinationFolder, Player player, Consumer<Boolean> onComplete) {
        if (!NightbreakAccount.hasToken()) {
            if (player != null && player.isOnline()) {
                player.sendMessage("§c[Nightbreak] No token registered. Use /nightbreakLogin <token> first.");
            }
            onComplete.accept(false);
            return;
        }

        // First check access
        checkAccessAsync(slug, accessInfo -> {
            if (accessInfo == null || !accessInfo.hasAccess) {
                if (player != null && player.isOnline()) {
                    player.sendMessage("§c[Nightbreak] You don't have access to this content.");
                    if (accessInfo != null) {
                        showAccessLinks(player, accessInfo);
                    }
                }
                onComplete.accept(false);
                return;
            }

            // Get version info for filename
            NightbreakAccount.VersionInfo versionInfo = versionCache.get(slug);
            String fileName = versionInfo != null && versionInfo.fileName != null
                ? versionInfo.fileName
                : slug + ".zip";

            File destinationFile = new File(destinationFolder, fileName);

            if (player != null && player.isOnline()) {
                player.sendMessage("§a[Nightbreak] Starting download of " + slug + "...");
            }

            // Run download async
            Bukkit.getScheduler().runTaskAsynchronously(MagmaCore.getInstance().getRequestingPlugin(), () -> {
                final long[] lastUpdate = {0};
                boolean success = NightbreakAccount.getInstance().download(slug, destinationFile, null,
                    (bytesDownloaded, totalBytes) -> {
                        // Throttle progress updates to every 2 seconds
                        if (player != null && player.isOnline() && System.currentTimeMillis() - lastUpdate[0] > 2000) {
                            lastUpdate[0] = System.currentTimeMillis();
                            String progress = totalBytes > 0
                                ? String.format("%.1f%%", (bytesDownloaded * 100.0 / totalBytes))
                                : formatBytes(bytesDownloaded);
                            Bukkit.getScheduler().runTask(MagmaCore.getInstance().getRequestingPlugin(), () -> {
                                if (player.isOnline()) {
                                    player.sendMessage("§7[Nightbreak] Downloading... " + progress);
                                }
                            });
                        }
                    });

                // Return to main thread for callback
                Bukkit.getScheduler().runTask(MagmaCore.getInstance().getRequestingPlugin(), () -> {
                    if (success) {
                        if (player != null && player.isOnline()) {
                            player.sendMessage("§a[Nightbreak] Download complete! File saved to imports folder.");
                            player.sendMessage("§a[Nightbreak] Use /em reload (or plugin reload) to install the content.");
                        }
                    } else {
                        if (player != null && player.isOnline()) {
                            player.sendMessage("§c[Nightbreak] Download failed. Please try again later.");
                        }
                    }
                    onComplete.accept(success);
                });
            });
        });
    }

    /**
     * Shows access purchase links to a player.
     */
    public static void showAccessLinks(Player player, NightbreakAccount.AccessInfo accessInfo) {
        player.sendMessage("§6----------------------------------------------------");
        player.sendMessage("§eYou can get access to this content through:");
        if (accessInfo.patreonLink != null && !accessInfo.patreonLink.isEmpty()) {
            player.sendMessage("§6• Patreon: §9" + accessInfo.patreonLink);
        }
        if (accessInfo.itchLink != null && !accessInfo.itchLink.isEmpty()) {
            player.sendMessage("§6• itch.io: §9" + accessInfo.itchLink);
        }
        if ((accessInfo.patreonLink == null || accessInfo.patreonLink.isEmpty()) &&
            (accessInfo.itchLink == null || accessInfo.itchLink.isEmpty())) {
            player.sendMessage("§6• Visit: §9https://nightbreak.io");
        }
        player.sendMessage("§6----------------------------------------------------");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * Shuts down the manager and clears caches.
     */
    public static void shutdown() {
        clearCache();
    }
}
