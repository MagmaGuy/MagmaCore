package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class NightbreakPluginUpdater {
    public static final String AUTO_DOWNLOAD_CONFIG_PATH = "nightbreak.autoDownloadPluginUpdates";
    private static final long UPDATE_REFRESH_INTERVAL_TICKS = 20L * 60L * 60L;
    private static final long INITIAL_UPDATE_REFRESH_DELAY_TICKS = 20L * 60L * 5L;
    private static final long BOOT_STAGGER_MAX_TICKS = 20L * 60L * 2L;
    private static final long AUTO_PLUGIN_UPDATE_EXTRA_DELAY_TICKS = 20L * 30L;
    private static final long AUTO_CONTENT_UPDATE_EXTRA_DELAY_TICKS = 20L * 90L;
    private static final int SPIGOT_FALLBACK_TIMEOUT_MS = 3000;
    private static final List<String> AUTO_DOWNLOAD_CONFIG_COMMENTS = List.of(
            "When true, this plugin automatically downloads available plugin updates",
            "and content update files on startup. Downloaded plugin and content",
            "updates are used after the server restarts.",
            "Automatic plugin downloads require a valid account token and an active supporter",
            "Patreon membership. Leave this false if you prefer to use the in-game update button.");
    private static final Map<String, PluginUpdateCheck> CACHED_UPDATE_CHECKS = new ConcurrentHashMap<>();
    private static final Map<String, Long> CACHED_UPDATE_CHECK_TIMES = new ConcurrentHashMap<>();
    private static final Set<String> RUNNING_UPDATE_CHECKS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Integer> UPDATE_CHECK_TASKS = new ConcurrentHashMap<>();
    private static final Set<String> RUNNING_AUTO_CONTENT_UPDATES = ConcurrentHashMap.newKeySet();

    private NightbreakPluginUpdater() {
    }

    public enum DownloadStatus {
        DOWNLOADED,
        UP_TO_DATE,
        NO_TOKEN,
        NO_ACCESS,
        AUTH_FAILURE,
        NIGHTBREAK_UNREACHABLE,
        DOWNLOAD_FAILED,
        CHECKSUM_FAILED
    }

    public record PluginUpdateCheck(boolean updateAvailable,
                                    boolean nightbreakReachable,
                                    boolean spigotFallbackUsed,
                                    String localVersion,
                                    String remoteVersion,
                                    String downloadPageUrl,
                                    NightbreakAccount.VersionInfo versionInfo) {
    }

    public record PluginUpdateDownload(DownloadStatus status,
                                       String localVersion,
                                       String remoteVersion,
                                       String downloadPageUrl,
                                       File downloadedFile,
                                       String detail) {
        public boolean downloaded() {
            return status == DownloadStatus.DOWNLOADED;
        }
    }

    public record CachedPluginUpdateCheck(PluginUpdateCheck check,
                                          boolean checking,
                                          long checkedAtMillis) {
        public boolean hasResult() {
            return check != null;
        }
    }

    public static boolean setAutoDownloadConfigDefault(FileConfiguration fileConfiguration) {
        boolean value = fileConfiguration.getBoolean(AUTO_DOWNLOAD_CONFIG_PATH, false);
        fileConfiguration.addDefault(AUTO_DOWNLOAD_CONFIG_PATH, false);
        fileConfiguration.setComments(AUTO_DOWNLOAD_CONFIG_PATH, AUTO_DOWNLOAD_CONFIG_COMMENTS);
        return value;
    }

    public static void ensureAutoDownloadConfigDefault(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        setAutoDownloadConfigDefault(config);
        config.options().copyDefaults(true);
        plugin.saveConfig();
    }

    public static void autoDownloadPluginUpdateIfEnabled(JavaPlugin plugin, NightbreakPluginSpec spec) {
        ensureAutoDownloadConfigDefault(plugin);
        startPluginUpdateMonitor(plugin, spec);
        if (!isAutoDownloadPluginUpdatesEnabled(plugin)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!plugin.isEnabled()) return;
            plugin.getLogger().info("Automatic update downloads are enabled.");
            downloadPluginUpdateAsync(plugin, spec, Bukkit.getConsoleSender(), result -> {
                if (result.downloaded()) {
                    plugin.getLogger().info("Downloaded " + spec.displayName() + " " + result.remoteVersion() + ". Restart the server to use it.");
                }
            });
        }, initialRefreshDelayTicks(plugin, spec) + AUTO_PLUGIN_UPDATE_EXTRA_DELAY_TICKS);
    }

    public static void startPluginUpdateMonitor(JavaPlugin plugin, NightbreakPluginSpec spec) {
        if (plugin == null || spec == null) return;
        String key = cacheKey(plugin, spec);
        Integer existingTaskId = UPDATE_CHECK_TASKS.get(key);
        if (existingTaskId != null
                && (Bukkit.getScheduler().isQueued(existingTaskId)
                || Bukkit.getScheduler().isCurrentlyRunning(existingTaskId))) {
            return;
        }
        int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin,
                () -> refreshPluginUpdateCheckAsync(plugin, spec, null),
                initialRefreshDelayTicks(plugin, spec),
                UPDATE_REFRESH_INTERVAL_TICKS);
        if (taskId != -1) {
            UPDATE_CHECK_TASKS.put(key, taskId);
        }
    }

    public static void refreshPluginUpdateCheckAsync(JavaPlugin plugin,
                                                     NightbreakPluginSpec spec,
                                                     Consumer<PluginUpdateCheck> callback) {
        if (plugin == null || spec == null) return;
        String key = cacheKey(plugin, spec);
        if (!RUNNING_UPDATE_CHECKS.add(key)) return;
        String spigotResourceId = NightbreakPluginCatalog.forSpec(spec)
                .map(NightbreakPluginCatalog.Entry::spigotResourceId)
                .orElse("");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PluginUpdateCheck check = checkForUpdate(plugin, spec, spigotResourceId);
                cacheUpdateCheck(plugin, spec, check);
                if (callback != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> callback.accept(check));
                }
            } finally {
                RUNNING_UPDATE_CHECKS.remove(key);
            }
        });
    }

    public static <T extends NightbreakManagedContent> void autoDownloadContentUpdatesIfEnabled(JavaPlugin plugin,
                                                                                                NightbreakPluginSpec spec,
                                                                                                Supplier<List<T>> packagesSupplier,
                                                                                                AtomicBoolean guard,
                                                                                                Consumer<CommandSender> reloadAction) {
        if (plugin == null || spec == null || packagesSupplier == null || !spec.hasContentPackages()) return;
        if (!isAutoDownloadPluginUpdatesEnabled(plugin)) return;
        String key = cacheKey(plugin, spec);
        if (!RUNNING_AUTO_CONTENT_UPDATES.add(key)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                if (!plugin.isEnabled() || !isAutoDownloadPluginUpdatesEnabled(plugin)) return;
                plugin.getLogger().info("Automatic content update downloads are enabled. Downloaded content updates will be used after the server restarts.");
                NightbreakBulkDownloader.execute(plugin,
                        spec.displayName(),
                        Bukkit.getConsoleSender(),
                        packagesSupplier.get(),
                        true,
                        guard,
                        reloadAction,
                        false);
            } finally {
                RUNNING_AUTO_CONTENT_UPDATES.remove(key);
            }
        }, initialRefreshDelayTicks(plugin, spec) + AUTO_CONTENT_UPDATE_EXTRA_DELAY_TICKS);
    }

    public static CachedPluginUpdateCheck getCachedUpdateCheck(JavaPlugin plugin, NightbreakPluginSpec spec) {
        String key = cacheKey(plugin, spec);
        return new CachedPluginUpdateCheck(CACHED_UPDATE_CHECKS.get(key),
                RUNNING_UPDATE_CHECKS.contains(key),
                CACHED_UPDATE_CHECK_TIMES.getOrDefault(key, 0L));
    }

    public static void checkForUpdateAsync(JavaPlugin plugin,
                                           NightbreakPluginSpec spec,
                                           String spigotResourceId,
                                           Consumer<PluginUpdateCheck> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PluginUpdateCheck check = checkForUpdate(plugin, spec, spigotResourceId);
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(check));
        });
    }

    public static PluginUpdateCheck checkForUpdate(JavaPlugin plugin,
                                                   NightbreakPluginSpec spec,
                                                   String spigotResourceId) {
        String localVersion = cleanVersion(plugin.getDescription().getVersion());
        NightbreakAccount.VersionInfo versionInfo = NightbreakAccount.getPublicPluginVersion(spec.pluginSlug());
        boolean nightbreakReachable = versionInfo != null && versionInfo.version != null && !versionInfo.version.isBlank();
        boolean spigotFallbackUsed = false;
        String remoteVersion = nightbreakReachable ? versionInfo.version : null;

        if (!nightbreakReachable && spigotResourceId != null && !spigotResourceId.isBlank()) {
            remoteVersion = readSpigotVersion(spigotResourceId);
            spigotFallbackUsed = remoteVersion != null && !remoteVersion.isBlank();
        }

        boolean updateAvailable = remoteVersion != null && compareVersions(remoteVersion, localVersion) > 0;
        return new PluginUpdateCheck(updateAvailable,
                nightbreakReachable,
                spigotFallbackUsed,
                localVersion,
                remoteVersion,
                spec.downloadPageUrl(),
                versionInfo);
    }

    public static void downloadPluginUpdateAsync(JavaPlugin plugin,
                                                 NightbreakPluginSpec spec,
                                                 CommandSender sender,
                                                 Consumer<PluginUpdateDownload> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PluginUpdateDownload result = downloadPluginUpdate(plugin, spec, sender);
            Bukkit.getScheduler().runTask(plugin, () -> {
                sendDownloadResult(sender, spec, result);
                if (result.downloaded()) {
                    NightbreakPluginUpdateMessages.broadcastRestartRequired(plugin, spec, result, sender);
                }
                if (callback != null) callback.accept(result);
            });
        });
    }

    private static PluginUpdateDownload downloadPluginUpdate(JavaPlugin plugin,
                                                             NightbreakPluginSpec spec,
                                                             CommandSender sender) {
        String localVersion = cleanVersion(plugin.getDescription().getVersion());
        String downloadPageUrl = spec.downloadPageUrl();
        NightbreakAccount.VersionInfo versionInfo = NightbreakAccount.getPublicPluginVersion(spec.pluginSlug());
        if (versionInfo == null || versionInfo.version == null || versionInfo.version.isBlank()) {
            return new PluginUpdateDownload(DownloadStatus.NIGHTBREAK_UNREACHABLE,
                    localVersion, null, downloadPageUrl, null, null);
        }

        String remoteVersion = versionInfo.version;
        cacheUpdateCheck(plugin, spec, new PluginUpdateCheck(compareVersions(remoteVersion, localVersion) > 0,
                true,
                false,
                localVersion,
                remoteVersion,
                downloadPageUrl,
                versionInfo));
        if (compareVersions(remoteVersion, localVersion) <= 0) {
            return new PluginUpdateDownload(DownloadStatus.UP_TO_DATE,
                    localVersion, remoteVersion, downloadPageUrl, null, null);
        }

        if (!NightbreakAccount.hasToken()) {
            return new PluginUpdateDownload(DownloadStatus.NO_TOKEN,
                    localVersion, remoteVersion, downloadPageUrl, null, null);
        }
        if (NightbreakAccount.hasAuthFailure()) {
            return new PluginUpdateDownload(DownloadStatus.AUTH_FAILURE,
                    localVersion, remoteVersion, downloadPageUrl, null, null);
        }

        NightbreakAccount account = NightbreakAccount.getInstance();
        if (account == null) {
            return new PluginUpdateDownload(DownloadStatus.NO_TOKEN,
                    localVersion, remoteVersion, downloadPageUrl, null, null);
        }

        File updateFolder = resolveUpdateFolder(plugin);
        if (!updateFolder.exists() && !updateFolder.mkdirs()) {
            return new PluginUpdateDownload(DownloadStatus.DOWNLOAD_FAILED,
                    localVersion, remoteVersion, downloadPageUrl, null,
                    "Could not create update folder: " + updateFolder.getPath());
        }

        String targetName = currentJarName(plugin, versionInfo.fileName);
        File tempFile = new File(updateFolder, targetName + ".download");
        File downloadedFile = new File(updateFolder, targetName);
        if (tempFile.exists() && !tempFile.delete()) {
            return new PluginUpdateDownload(DownloadStatus.DOWNLOAD_FAILED,
                    localVersion, remoteVersion, downloadPageUrl, null,
                    "Could not clear previous temporary download.");
        }

        final long[] lastProgressMessage = {0L};
        NightbreakAccount.PluginDownloadResult downloadResult = account.downloadPluginUpdate(spec.pluginSlug(), tempFile, (bytesDownloaded, totalBytes) -> {
            if (!canMessage(sender)) return;
            long now = System.currentTimeMillis();
            if (now - lastProgressMessage[0] < 2000L) return;
            lastProgressMessage[0] = now;
            String progress = totalBytes > 0
                    ? String.format(Locale.ROOT, "%.1f%%", bytesDownloaded * 100.0 / totalBytes)
                    : formatBytes(bytesDownloaded);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (canMessage(sender)) {
                    Logger.sendSimpleMessage(sender, "&7[" + spec.displayName() + "] Downloading plugin update... " + progress);
                }
            });
        });

        if (!downloadResult.success || !tempFile.exists()) {
            if (tempFile.exists()) tempFile.delete();
            return new PluginUpdateDownload(downloadStatusFor(downloadResult),
                    localVersion, remoteVersion, downloadPageUrl, null, downloadResult.displayDetail());
        }

        if (versionInfo.checksum != null && !versionInfo.checksum.isBlank()) {
            String actualChecksum;
            try {
                actualChecksum = sha256(tempFile);
            } catch (IllegalStateException e) {
                tempFile.delete();
                return new PluginUpdateDownload(DownloadStatus.CHECKSUM_FAILED,
                        localVersion, remoteVersion, downloadPageUrl, null, e.getMessage());
            }
            if (!versionInfo.checksum.equalsIgnoreCase(actualChecksum)) {
                tempFile.delete();
                return new PluginUpdateDownload(DownloadStatus.CHECKSUM_FAILED,
                        localVersion, remoteVersion, downloadPageUrl, null, "Expected " + versionInfo.checksum + ", got " + actualChecksum);
            }
        }

        try {
            moveReplacing(tempFile, downloadedFile);
        } catch (IOException e) {
            tempFile.delete();
            return new PluginUpdateDownload(DownloadStatus.DOWNLOAD_FAILED,
                    localVersion, remoteVersion, downloadPageUrl, null, e.getMessage());
        }

        return new PluginUpdateDownload(DownloadStatus.DOWNLOADED,
                localVersion, remoteVersion, downloadPageUrl, downloadedFile, null);
    }

    public static void sendDownloadResult(CommandSender sender,
                                          NightbreakPluginSpec spec,
                                          PluginUpdateDownload result) {
        if (!canMessage(sender)) return;
        NightbreakPluginUpdateMessages.sendResult(sender, spec, result);
    }

    private static DownloadStatus downloadStatusFor(NightbreakAccount.PluginDownloadResult downloadResult) {
        if (downloadResult.responseCode == 401 || NightbreakAccount.hasAuthFailure()) {
            return DownloadStatus.AUTH_FAILURE;
        }
        if ("NO_TOKEN".equalsIgnoreCase(downloadResult.error)) {
            return DownloadStatus.NO_TOKEN;
        }
        if (downloadResult.responseCode == 403) {
            return DownloadStatus.NO_ACCESS;
        }
        if (downloadResult.responseCode == 0 || "NETWORK_ERROR".equalsIgnoreCase(downloadResult.error)) {
            return DownloadStatus.NIGHTBREAK_UNREACHABLE;
        }
        return DownloadStatus.DOWNLOAD_FAILED;
    }

    public static boolean isAutoDownloadPluginUpdatesEnabled(JavaPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) return false;
        try {
            return YamlConfiguration.loadConfiguration(configFile).getBoolean(AUTO_DOWNLOAD_CONFIG_PATH, false);
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to read " + AUTO_DOWNLOAD_CONFIG_PATH + " from config.yml: " + exception.getMessage());
            return false;
        }
    }

    public static boolean setAutoDownloadPluginUpdatesEnabled(JavaPlugin plugin, boolean enabled) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        try {
            if (!configFile.exists()) {
                plugin.getDataFolder().mkdirs();
                configFile.createNewFile();
            }
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(configFile);
            configuration.set(AUTO_DOWNLOAD_CONFIG_PATH, enabled);
            configuration.setComments(AUTO_DOWNLOAD_CONFIG_PATH, AUTO_DOWNLOAD_CONFIG_COMMENTS);
            configuration.save(configFile);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to write " + AUTO_DOWNLOAD_CONFIG_PATH + " to config.yml: " + exception.getMessage());
            return false;
        }
    }

    public static int compareVersions(String left, String right) {
        int[] leftParts = parseVersionParts(left);
        int[] rightParts = parseVersionParts(right);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int leftPart = i < leftParts.length ? leftParts[i] : 0;
            int rightPart = i < rightParts.length ? rightParts[i] : 0;
            if (leftPart != rightPart) return Integer.compare(leftPart, rightPart);
        }
        return 0;
    }

    public static String cleanVersion(String version) {
        if (version == null) return "0";
        String cleaned = version.trim();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) cleaned = cleaned.substring(1);
        int suffix = cleaned.indexOf('-');
        if (suffix != -1) cleaned = cleaned.substring(0, suffix);
        suffix = cleaned.indexOf('+');
        if (suffix != -1) cleaned = cleaned.substring(0, suffix);
        return cleaned.isBlank() ? "0" : cleaned;
    }

    private static int[] parseVersionParts(String version) {
        String[] rawParts = cleanVersion(version).split("\\.");
        int[] parts = new int[rawParts.length];
        for (int i = 0; i < rawParts.length; i++) {
            String part = rawParts[i].replaceAll("[^0-9].*$", "");
            if (part.isBlank()) {
                parts[i] = 0;
                continue;
            }
            try {
                parts[i] = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                parts[i] = 0;
            }
        }
        return parts;
    }

    private static String readSpigotVersion(String resourceId) {
        try {
            URLConnection connection = new URL("https://api.spigotmc.org/legacy/update.php?resource=" + resourceId).openConnection();
            connection.setConnectTimeout(SPIGOT_FALLBACK_TIMEOUT_MS);
            connection.setReadTimeout(SPIGOT_FALLBACK_TIMEOUT_MS);
            try (Scanner scanner = new Scanner(connection.getInputStream(), StandardCharsets.UTF_8)) {
                scanner.useDelimiter("\\A");
                return scanner.hasNext() ? scanner.next().trim() : null;
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static long initialRefreshDelayTicks(JavaPlugin plugin, NightbreakPluginSpec spec) {
        return INITIAL_UPDATE_REFRESH_DELAY_TICKS + Math.floorMod(cacheKey(plugin, spec).hashCode(), (int) BOOT_STAGGER_MAX_TICKS);
    }

    private static void cacheUpdateCheck(JavaPlugin plugin, NightbreakPluginSpec spec, PluginUpdateCheck check) {
        String key = cacheKey(plugin, spec);
        CACHED_UPDATE_CHECKS.put(key, check);
        CACHED_UPDATE_CHECK_TIMES.put(key, System.currentTimeMillis());
    }

    private static String cacheKey(JavaPlugin plugin, NightbreakPluginSpec spec) {
        String pluginName = plugin == null ? "" : plugin.getName();
        String slug = spec == null ? "" : spec.pluginSlug();
        return pluginName.toLowerCase(Locale.ROOT) + ":" + slug.toLowerCase(Locale.ROOT);
    }

    private static File resolveUpdateFolder(JavaPlugin plugin) {
        try {
            Object updateFolder = Bukkit.getServer().getClass().getMethod("getUpdateFolderFile").invoke(Bukkit.getServer());
            if (updateFolder instanceof File file) return file;
        } catch (ReflectiveOperationException ignored) {
            // Older Bukkit APIs only expose the folder name; fall back below.
        }
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        return new File(pluginsFolder, "update");
    }

    private static String currentJarName(JavaPlugin plugin, String remoteFileName) {
        File currentJar = currentPluginFile(plugin);
        if (currentJar != null && currentJar.isFile()) return currentJar.getName();
        if (remoteFileName != null && remoteFileName.endsWith(".jar")) return remoteFileName;
        return plugin.getName() + ".jar";
    }

    private static File currentPluginFile(JavaPlugin plugin) {
        try {
            URL location = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            File file = new File(location.toURI());
            return file.isFile() ? file : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void moveReplacing(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream inputStream = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder builder = new StringBuilder();
            for (byte b : digest.digest()) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not calculate checksum", e);
        }
    }

    private static boolean canMessage(CommandSender sender) {
        return sender != null && (!(sender instanceof Player player) || player.isOnline());
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
