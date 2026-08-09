package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Utility class for managing Nightbreak content downloads and access checks.
 * Provides async operations and caching for efficient content management.
 */
public class NightbreakContentManager {

    @Getter
    private static final Map<String, NightbreakAccount.AccessInfo> accessCache = new ConcurrentHashMap<>();
    @Getter
    private static final Map<String, NightbreakAccount.VersionInfo> versionCache = new ConcurrentHashMap<>();

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
        refreshVersionCache(MagmaCore.getInstance().getRequestingPlugin());
    }

    public static void refreshVersionCache(JavaPlugin ownerPlugin) {
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
        checkAccessAsync(MagmaCore.getInstance().getRequestingPlugin(), slug, callback);
    }

    public static void checkAccessAsync(JavaPlugin ownerPlugin, String slug, Consumer<NightbreakAccount.AccessInfo> callback) {
        OperationGate lifecycleGate =
                NightbreakBulkDownloader.captureOperationGate(ownerPlugin);
        checkAccessAsync(ownerPlugin, slug, lifecycleGate, callback);
    }

    static void checkAccessAsync(JavaPlugin ownerPlugin,
                                 String slug,
                                 OperationGate lifecycleGate,
                                 Consumer<NightbreakAccount.AccessInfo> callback) {
        checkAccessAsync(ownerPlugin, slug, lifecycleGate::isCurrent, callback);
    }

    private static void checkAccessAsync(JavaPlugin ownerPlugin,
                                         String slug,
                                         BooleanSupplier operationCurrent,
                                         Consumer<NightbreakAccount.AccessInfo> callback) {
        if (!operationCurrent.getAsBoolean()) {
            return;
        }
        if (!NightbreakAccount.hasToken()) {
            callback.accept(null);
            return;
        }
        if (NightbreakAccount.hasAuthFailure()) {
            callback.accept(null);
            return;
        }

        // Check cache first
        if (accessCache.containsKey(slug) && !isCacheStale()) {
            if (operationCurrent.getAsBoolean()) callback.accept(accessCache.get(slug));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(ownerPlugin, () -> {
            if (!operationCurrent.getAsBoolean()) {
                return;
            }
            NightbreakAccount account = NightbreakAccount.getInstance();
            NightbreakAccount.AccessInfo info = account == null ? null : account.checkAccess(slug);
            if (info != null && operationCurrent.getAsBoolean()) {
                accessCache.put(slug, info);
            }
            if (!operationCurrent.getAsBoolean()) {
                return;
            }
            // Return to main thread for callback
            Bukkit.getScheduler().runTask(ownerPlugin, () -> {
                if (operationCurrent.getAsBoolean()) callback.accept(info);
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
        downloadAsync(MagmaCore.getInstance().getRequestingPlugin(), slug, destinationFolder, player, onComplete);
    }

    public static void downloadAsync(JavaPlugin ownerPlugin,
                                     String slug,
                                     File destinationFolder,
                                     Player player,
                                     Consumer<Boolean> onComplete) {
        downloadAsync(ownerPlugin, slug, destinationFolder, player,
                NightbreakBulkDownloader.captureOperationGate(ownerPlugin), onComplete);
    }

    static void downloadAsync(JavaPlugin ownerPlugin,
                              String slug,
                              File destinationFolder,
                              Player player,
                              OperationGate publicationGate,
                              Consumer<Boolean> onComplete) {
        BooleanSupplier operationCurrent = publicationGate == null
                ? () -> true
                : publicationGate::isCurrent;
        if (!operationCurrent.getAsBoolean()) {
            return;
        }
        if (!NightbreakAccount.hasToken()) {
            if (player != null && player.isOnline()) {
                player.sendMessage("§c[Nightbreak] No token registered. Use /nightbreaklogin <token> first.");
            }
            onComplete.accept(false);
            return;
        }

        // First check access
        checkAccessAsync(ownerPlugin, slug, operationCurrent, accessInfo -> {
            if (!operationCurrent.getAsBoolean()) {
                return;
            }
            if (accessInfo == null || !accessInfo.hasAccess) {
                if (player != null && player.isOnline()) {
                    if (accessInfo == null && NightbreakAccount.hasAuthFailure()) {
                        NightbreakSetupMenuHelper.sendTokenUpdatePrompt(player, ownerPlugin.getName());
                    } else {
                        player.sendMessage("§c[Nightbreak] You don't have access to this content.");
                    }
                    if (accessInfo != null && !accessInfo.hasAccess) {
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

            Path destinationRoot = destinationFolder.toPath().toAbsolutePath().normalize();
            Path destinationFile = destinationRoot.resolve(fileName).normalize();
            if (!destinationRoot.equals(destinationFile.getParent())) {
                Logger.warn("Refusing unsafe Nightbreak download filename for '" + slug + "': " + fileName);
                onComplete.accept(false);
                return;
            }

            if (player != null && player.isOnline()) {
                player.sendMessage("§a[Nightbreak] Starting download of " + slug + "...");
            }

            // Run download async
            Bukkit.getScheduler().runTaskAsynchronously(ownerPlugin, () -> {
                final long[] lastUpdate = {0};
                Path temporaryFile = null;
                boolean success = false;
                try {
                    if (!operationCurrent.getAsBoolean()) return;
                    Files.createDirectories(destinationRoot);
                    temporaryFile = Files.createTempFile(destinationRoot, ".nightbreak-", ".download");
                    NightbreakAccount account = NightbreakAccount.getInstance();
                    boolean downloaded = account != null && account.download(slug, temporaryFile.toFile(), null,
                            (bytesDownloaded, totalBytes) -> {
                                if (!operationCurrent.getAsBoolean()) return;
                                // Throttle progress updates to every 2 seconds
                                if (player != null && player.isOnline() && System.currentTimeMillis() - lastUpdate[0] > 2000) {
                                    lastUpdate[0] = System.currentTimeMillis();
                                    String progress = totalBytes > 0
                                            ? String.format("%.1f%%", (bytesDownloaded * 100.0 / totalBytes))
                                            : formatBytes(bytesDownloaded);
                                    Bukkit.getScheduler().runTask(ownerPlugin, () -> {
                                        if (operationCurrent.getAsBoolean() && player.isOnline()) {
                                            player.sendMessage("§7[Nightbreak] Downloading... " + progress);
                                        }
                                    });
                                }
                            });

                    if (downloaded && operationCurrent.getAsBoolean()) {
                        success = publicationGate == null
                                ? publishDownloadedFile(temporaryFile, destinationFile)
                                : publicationGate.publish(temporaryFile, destinationFile);
                    }
                } catch (IOException | RuntimeException exception) {
                    Logger.warn("Failed to publish Nightbreak download '" + slug + "': " + exception.getMessage());
                } finally {
                    if (temporaryFile != null) {
                        try {
                            Files.deleteIfExists(temporaryFile);
                        } catch (IOException exception) {
                            Logger.warn("Failed to remove temporary Nightbreak download " + temporaryFile + ": " + exception.getMessage());
                        }
                    }
                    completeDownload(ownerPlugin, player, operationCurrent, success, onComplete);
                }
            });
        });
    }

    private static boolean publishDownloadedFile(Path temporaryFile, Path destinationFile) throws IOException {
        try {
            Files.move(temporaryFile, destinationFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporaryFile, destinationFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    private static void completeDownload(JavaPlugin ownerPlugin,
                                         Player player,
                                         BooleanSupplier operationCurrent,
                                         boolean success,
                                         Consumer<Boolean> onComplete) {
        if (!operationCurrent.getAsBoolean()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(ownerPlugin, () -> {
                if (!operationCurrent.getAsBoolean()) {
                    return;
                }
                if (success) {
                    if (player != null && player.isOnline()) {
                        player.sendMessage("§a[Nightbreak] Download complete! File saved to imports folder.");
                    }
                } else if (player != null && player.isOnline()) {
                    player.sendMessage("§c[Nightbreak] Download failed. Please try again later.");
                }
                onComplete.accept(success);
            });
        } catch (RuntimeException ignored) {
            // A lifecycle shutdown can make Bukkit reject the handoff. The
            // generation gate already prevents the old operation from being
            // observed by the replacement lifecycle.
        }
    }

    interface OperationGate {
        boolean isCurrent();

        boolean publish(Path temporaryFile, Path destinationFile) throws IOException;
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
