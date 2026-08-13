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
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final String SPIGOT_UPDATE_BASE_URL = "https://api.spigotmc.org";
    private static final Object SPIGOT_TEST_BASE_URL_LOCK = new Object();
    private static volatile String scopedSpigotTestBaseUrl;
    private static final AtomicInteger PRODUCTION_SPIGOT_ORIGIN_SELECTIONS =
            new AtomicInteger();
    private static final List<String> AUTO_DOWNLOAD_CONFIG_COMMENTS = List.of(
            "When true, this plugin automatically downloads available plugin updates",
            "and content update files on startup. Downloaded plugin and content",
            "updates are used after the server restarts.",
            "Automatic plugin downloads require a valid account token and an active supporter",
            "Patreon membership. Leave this false if you prefer to use the in-game update button.");
    private static final Map<String, PluginUpdateCheck> CACHED_UPDATE_CHECKS = new ConcurrentHashMap<>();
    private static final Map<String, Long> CACHED_UPDATE_CHECK_TIMES = new ConcurrentHashMap<>();
    private static final Set<String> RUNNING_UPDATE_CHECKS = ConcurrentHashMap.newKeySet();
    private static final Set<String> RUNNING_PLUGIN_DOWNLOADS =
            ConcurrentHashMap.newKeySet();
    private static final Map<String, Integer> UPDATE_CHECK_TASKS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> AUTO_PLUGIN_UPDATE_TASKS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> AUTO_CONTENT_UPDATE_TASKS = new ConcurrentHashMap<>();
    private static final Set<String> RUNNING_AUTO_CONTENT_UPDATES = ConcurrentHashMap.newKeySet();
    private static final Object PLUGIN_LIFECYCLE_LOCK = new Object();
    private static final Map<JavaPlugin, Long> PLUGIN_GENERATIONS = new IdentityHashMap<>();
    private static final Map<JavaPlugin, CopyOnWriteArrayList<PluginUpdateListenerRegistration>> PLUGIN_UPDATE_LISTENERS =
            new IdentityHashMap<>();
    private static final NightbreakPluginAsyncWorkTracker ASYNC_WORK =
            new NightbreakPluginAsyncWorkTracker();

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

    /**
     * Removable registration for a successfully downloaded plugin-update listener.
     */
    public interface ListenerRegistration extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Registers a listener that runs after a plugin update passes checksum and
     * plugin-artifact validation and has been atomically placed in Bukkit's
     * update directory.
     *
     * <p>The listener runs on the updater's asynchronous download thread. It must
     * schedule any Bukkit API work back onto the server thread. Closing the returned
     * registration is idempotent; {@link #shutdown(JavaPlugin)} also removes it.</p>
     */
    public static ListenerRegistration onPluginUpdateDownloaded(JavaPlugin plugin,
                                                                Consumer<PluginUpdateDownload> listener) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(listener, "listener");
        PluginUpdateListenerRegistration registration =
                new PluginUpdateListenerRegistration(plugin, listener);
        synchronized (PLUGIN_LIFECYCLE_LOCK) {
            PLUGIN_UPDATE_LISTENERS
                    .computeIfAbsent(plugin, ignored -> new CopyOnWriteArrayList<>())
                    .add(registration);
        }
        return registration;
    }

    /**
     * Invalidates updater work associated with this exact plugin instance.
     *
     * <p>This is required even when a plugin implements reload by directly
     * invoking {@code onDisable()} and {@code onEnable()} on the same object;
     * Bukkit does not cancel scheduler work for that pattern.</p>
     */
    public static void shutdown(JavaPlugin plugin) {
        if (plugin == null) return;
        NightbreakChangelogManager.shutdown(plugin);

        String pluginName = plugin.getName();
        List<NightbreakPluginAsyncWorkTracker.Work> activeWork;
        synchronized (PLUGIN_LIFECYCLE_LOCK) {
            PLUGIN_GENERATIONS.put(plugin, currentGenerationLocked(plugin) + 1L);
            CopyOnWriteArrayList<PluginUpdateListenerRegistration> registrations =
                    PLUGIN_UPDATE_LISTENERS.remove(plugin);
            if (registrations != null) {
                registrations.forEach(PluginUpdateListenerRegistration::markClosedFromShutdown);
            }
            activeWork = ASYNC_WORK.snapshot(plugin);
        }

        String keyPrefix = pluginName.toLowerCase(Locale.ROOT) + ":";
        cancelTasksForPlugin(UPDATE_CHECK_TASKS, keyPrefix);
        cancelTasksForPlugin(AUTO_PLUGIN_UPDATE_TASKS, keyPrefix);
        cancelTasksForPlugin(AUTO_CONTENT_UPDATE_TASKS, keyPrefix);
        RUNNING_AUTO_CONTENT_UPDATES.removeIf(key -> key.startsWith(keyPrefix));
        CACHED_UPDATE_CHECKS.keySet().removeIf(key -> key.startsWith(keyPrefix));
        CACHED_UPDATE_CHECK_TIMES.keySet().removeIf(key -> key.startsWith(keyPrefix));
        activeWork.forEach(NightbreakPluginAsyncWorkTracker.Work::requestShutdown);
        ASYNC_WORK.awaitQuiescence(pluginName, activeWork);
    }

    public static boolean setAutoDownloadConfigDefault(FileConfiguration fileConfiguration) {
        boolean value = fileConfiguration.getBoolean(AUTO_DOWNLOAD_CONFIG_PATH, false);
        fileConfiguration.addDefault(AUTO_DOWNLOAD_CONFIG_PATH, false);
        fileConfiguration.setComments(AUTO_DOWNLOAD_CONFIG_PATH, AUTO_DOWNLOAD_CONFIG_COMMENTS);
        return value;
    }

    public static void ensureAutoDownloadConfigDefault(JavaPlugin plugin) {
        // JavaPlugin caches getConfig() from its first call, and plugin "reloads" reuse the
        // same plugin instance — saving that cached snapshot here would clobber any config.yml
        // edits made since boot. Re-read from disk, and only write when the key is missing.
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        if (config.isSet(AUTO_DOWNLOAD_CONFIG_PATH)) return;
        setAutoDownloadConfigDefault(config);
        config.options().copyDefaults(true);
        plugin.saveConfig();
    }

    public static void autoDownloadPluginUpdateIfEnabled(JavaPlugin plugin, NightbreakPluginSpec spec) {
        ensureAutoDownloadConfigDefault(plugin);
        NightbreakChangelogManager.initialize(plugin, spec);
        startPluginUpdateMonitor(plugin, spec);
        if (!isAutoDownloadPluginUpdatesEnabled(plugin)) return;
        String key = cacheKey(plugin, spec);
        long generation = currentGeneration(plugin);
        AtomicInteger scheduledTaskId = new AtomicInteger(-1);
        int taskId = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            AUTO_PLUGIN_UPDATE_TASKS.remove(key, scheduledTaskId.get());
            if (!isCurrentGeneration(plugin, generation) || !plugin.isEnabled()) return;
            plugin.getLogger().info("Automatic update downloads are enabled.");
            downloadPluginUpdateAsync(plugin, spec, Bukkit.getConsoleSender(), result -> {
                if (isCurrentGeneration(plugin, generation) && result.downloaded()) {
                    plugin.getLogger().info("Downloaded " + spec.displayName() + " " + result.remoteVersion() + ". Restart the server to use it.");
                }
            });
        }, initialRefreshDelayTicks(plugin, spec) + AUTO_PLUGIN_UPDATE_EXTRA_DELAY_TICKS);
        scheduledTaskId.set(taskId);
        if (taskId != -1) {
            Integer previousTaskId = AUTO_PLUGIN_UPDATE_TASKS.put(key, taskId);
            cancelReplacedTask(previousTaskId, taskId);
        }
    }

    public static void startPluginUpdateMonitor(JavaPlugin plugin, NightbreakPluginSpec spec) {
        if (plugin == null || spec == null) return;
        String key = cacheKey(plugin, spec);
        long generation = currentGeneration(plugin);
        Integer existingTaskId = UPDATE_CHECK_TASKS.get(key);
        if (existingTaskId != null
                && (Bukkit.getScheduler().isQueued(existingTaskId)
                || Bukkit.getScheduler().isCurrentlyRunning(existingTaskId))) {
            return;
        }
        int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin,
                () -> {
                    if (isCurrentGeneration(plugin, generation)) {
                        refreshPluginUpdateCheckAsync(plugin, spec, null);
                    }
                },
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
        long generation = currentGeneration(plugin);
        if (!RUNNING_UPDATE_CHECKS.add(key)) return;
        String spigotResourceId = NightbreakPluginCatalog.forSpec(spec)
                .map(NightbreakPluginCatalog.Entry::spigotResourceId)
                .orElse("");
        NightbreakPluginAsyncWorkTracker.Work work = registerAsyncWork(plugin, generation);
        if (work == null) {
            RUNNING_UPDATE_CHECKS.remove(key);
            return;
        }
        try {
            var task = Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (!work.start()) {
                    RUNNING_UPDATE_CHECKS.remove(key);
                    return;
                }
                try {
                    if (!isCurrentGeneration(plugin, generation)) return;
                    PluginUpdateCheck check = checkForUpdate(plugin, spec, spigotResourceId);
                    if (!isCurrentGeneration(plugin, generation)) return;
                    cacheUpdateCheck(plugin, spec, check);
                    if (callback != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (isCurrentGeneration(plugin, generation)) {
                                callback.accept(check);
                            }
                        });
                    }
                } finally {
                    RUNNING_UPDATE_CHECKS.remove(key);
                    work.close();
                }
            });
            work.attach(task);
        } catch (RuntimeException exception) {
            RUNNING_UPDATE_CHECKS.remove(key);
            work.dispatchFailed();
            throw exception;
        }
    }

    public static <T extends NightbreakManagedContent> void autoDownloadContentUpdatesIfEnabled(JavaPlugin plugin,
                                                                                                NightbreakPluginSpec spec,
                                                                                                Supplier<List<T>> packagesSupplier,
                                                                                                AtomicBoolean guard,
                                                                                                Consumer<CommandSender> reloadAction) {
        if (plugin == null || spec == null || packagesSupplier == null || !spec.hasContentPackages()) return;
        NightbreakChangelogManager.registerContentSupplier(plugin, packagesSupplier);
        if (!isAutoDownloadPluginUpdatesEnabled(plugin)) return;
        String key = cacheKey(plugin, spec);
        long generation = currentGeneration(plugin);
        String runningKey = key + "#" + generation;
        if (!RUNNING_AUTO_CONTENT_UPDATES.add(runningKey)) return;
        AtomicInteger scheduledTaskId = new AtomicInteger(-1);
        int taskId = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            AUTO_CONTENT_UPDATE_TASKS.remove(key, scheduledTaskId.get());
            try {
                if (!isCurrentGeneration(plugin, generation)
                        || !plugin.isEnabled()
                        || !isAutoDownloadPluginUpdatesEnabled(plugin)) return;
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
                RUNNING_AUTO_CONTENT_UPDATES.remove(runningKey);
            }
        }, initialRefreshDelayTicks(plugin, spec) + AUTO_CONTENT_UPDATE_EXTRA_DELAY_TICKS);
        scheduledTaskId.set(taskId);
        if (taskId != -1) {
            Integer previousTaskId = AUTO_CONTENT_UPDATE_TASKS.put(key, taskId);
            cancelReplacedTask(previousTaskId, taskId);
        } else {
            RUNNING_AUTO_CONTENT_UPDATES.remove(runningKey);
        }
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
        long generation = currentGeneration(plugin);
        NightbreakPluginAsyncWorkTracker.Work work = registerAsyncWork(plugin, generation);
        if (work == null) return;
        try {
            var task = Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (!work.start()) return;
                try {
                    if (!isCurrentGeneration(plugin, generation)) return;
                    PluginUpdateCheck check = checkForUpdate(plugin, spec, spigotResourceId);
                    if (!isCurrentGeneration(plugin, generation)) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (isCurrentGeneration(plugin, generation)) {
                            callback.accept(check);
                        }
                    });
                } finally {
                    work.close();
                }
            });
            work.attach(task);
        } catch (RuntimeException exception) {
            work.dispatchFailed();
            throw exception;
        }
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
        long generation = currentGeneration(plugin);
        NightbreakPluginAsyncWorkTracker.Work work = registerAsyncWork(plugin, generation);
        if (work == null) return;
        try {
            var task = Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (!work.start()) return;
                try {
                    if (!isCurrentGeneration(plugin, generation)) return;
                    PluginUpdateDownload result = downloadPluginUpdate(plugin, spec, sender, generation);
                    if (!isCurrentGeneration(plugin, generation)) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!isCurrentGeneration(plugin, generation)) return;
                        sendDownloadResult(sender, spec, result);
                        if (result.downloaded()) {
                            NightbreakPluginUpdateMessages.broadcastRestartRequired(plugin, spec, result, sender);
                        }
                        if (callback != null) callback.accept(result);
                    });
                } finally {
                    work.close();
                }
            });
            work.attach(task);
        } catch (RuntimeException exception) {
            work.dispatchFailed();
            throw exception;
        }
    }

    static PluginUpdateDownload downloadPluginUpdate(JavaPlugin plugin,
                                                     NightbreakPluginSpec spec,
                                                     CommandSender sender) {
        return downloadPluginUpdate(plugin, spec, sender, currentGeneration(plugin));
    }

    private static PluginUpdateDownload downloadPluginUpdate(JavaPlugin plugin,
                                                              NightbreakPluginSpec spec,
                                                              CommandSender sender,
                                                              long generation) {
        String runningKey = cacheKey(plugin, spec);
        if (!RUNNING_PLUGIN_DOWNLOADS.add(runningKey)) {
            return new PluginUpdateDownload(
                    DownloadStatus.DOWNLOAD_FAILED,
                    cleanVersion(plugin.getDescription().getVersion()),
                    null,
                    spec.downloadPageUrl(),
                    null,
                    "A plugin update download is already in progress.");
        }
        try {
            return performPluginUpdateDownload(
                    plugin, spec, sender, generation);
        } finally {
            RUNNING_PLUGIN_DOWNLOADS.remove(runningKey);
        }
    }

    private static PluginUpdateDownload performPluginUpdateDownload(
            JavaPlugin plugin,
            NightbreakPluginSpec spec,
            CommandSender sender,
            long generation) {
        if (!isCurrentGeneration(plugin, generation)) {
            return new PluginUpdateDownload(
                    DownloadStatus.DOWNLOAD_FAILED,
                    cleanVersion(plugin.getDescription().getVersion()),
                    null,
                    spec.downloadPageUrl(),
                    null,
                    "Plugin lifecycle changed before the update download started.");
        }
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
        if (targetName == null) {
            return new PluginUpdateDownload(DownloadStatus.DOWNLOAD_FAILED,
                    localVersion, remoteVersion, downloadPageUrl, null,
                    "The update service returned an unsafe plugin filename.");
        }
        File tempFile = new File(
                updateFolder,
                targetName + "." + generation + "."
                        + UUID.randomUUID() + ".download");
        File downloadedFile = new File(updateFolder, targetName);
        File legacyTempFile =
                new File(updateFolder, targetName + ".download");
        if (legacyTempFile.exists() && !legacyTempFile.delete()) {
            return new PluginUpdateDownload(
                    DownloadStatus.DOWNLOAD_FAILED,
                    localVersion,
                    remoteVersion,
                    downloadPageUrl,
                    null,
                    "Could not clear a legacy temporary download.");
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

        DownloadedPluginJarValidator.ValidationResult jarValidation =
                DownloadedPluginJarValidator.validate(
                        tempFile, List.of(plugin.getName()), remoteVersion);
        if (!jarValidation.valid()) {
            tempFile.delete();
            return new PluginUpdateDownload(DownloadStatus.DOWNLOAD_FAILED,
                    localVersion, remoteVersion, downloadPageUrl, null,
                    jarValidation.detail());
        }

        synchronized (PLUGIN_LIFECYCLE_LOCK) {
            if (currentGenerationLocked(plugin) != generation) {
                tempFile.delete();
                return new PluginUpdateDownload(DownloadStatus.DOWNLOAD_FAILED,
                        localVersion, remoteVersion, downloadPageUrl, null,
                        "Plugin lifecycle changed before the downloaded update could be published.");
            }

            try {
                moveReplacing(tempFile, downloadedFile);
            } catch (IOException e) {
                tempFile.delete();
                return new PluginUpdateDownload(DownloadStatus.DOWNLOAD_FAILED,
                        localVersion, remoteVersion, downloadPageUrl, null, e.getMessage());
            }

            PluginUpdateDownload result = new PluginUpdateDownload(DownloadStatus.DOWNLOADED,
                    localVersion, remoteVersion, downloadPageUrl, downloadedFile, null);
            /*
             * Publication and listener delivery are one lifecycle transaction.
             * In particular, ResourcePackManager uses this listener to stage the
             * identical universal JAR for Geyser. A reload must either win before
             * the move (and cancel it) or wait until both destinations have been
             * handed the same artifact.
             */
            notifyPluginUpdateDownloaded(plugin, generation, result);
            return result;
        }
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
            String encodedResourceId = URLEncoder.encode(
                    resourceId, StandardCharsets.UTF_8);
            URLConnection connection = new URL(
                    spigotUpdateBaseUrl() +
                            "/legacy/update.php?resource=" +
                            encodedResourceId).openConnection();
            if (scopedSpigotTestBaseUrl != null &&
                    connection instanceof HttpURLConnection httpConnection) {
                httpConnection.setInstanceFollowRedirects(false);
            }
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
        if (Bukkit.getServer() != null) {
            try {
                Object updateFolder = Bukkit.getServer().getClass().getMethod("getUpdateFolderFile").invoke(Bukkit.getServer());
                if (updateFolder instanceof File file) return file;
            } catch (ReflectiveOperationException ignored) {
                // Older Bukkit APIs only expose the folder name; fall back below.
            }
        }
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        return new File(pluginsFolder, "update");
    }

    private static String currentJarName(JavaPlugin plugin, String remoteFileName) {
        File currentJar = currentPluginFile(plugin);
        if (currentJar != null && currentJar.isFile()) return currentJar.getName();
        if (remoteFileName != null && !remoteFileName.isBlank()) {
            return safeJarFileName(remoteFileName);
        }
        return plugin.getName() + ".jar";
    }

    private static String safeJarFileName(String candidate) {
        String value = candidate == null ? "" : candidate.trim();
        if (value.isBlank() ||
                !value.toLowerCase(Locale.ROOT).endsWith(".jar") ||
                value.indexOf('/') >= 0 ||
                value.indexOf('\\') >= 0 ||
                value.indexOf(':') >= 0 ||
                value.indexOf('\0') >= 0 ||
                ".".equals(value) ||
                "..".equals(value)) {
            return null;
        }
        return value;
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

    /**
     * Temporarily redirects the Spigot version fallback to a loopback fixture.
     * Production code has no configurable fallback origin.
     */
    static ScopedSpigotTestBaseUrl useLoopbackSpigotBaseUrlForTests(URI baseUri) {
        String normalized = normalizeLoopbackTestOrigin(baseUri);
        synchronized (SPIGOT_TEST_BASE_URL_LOCK) {
            if (scopedSpigotTestBaseUrl != null) {
                throw new IllegalStateException(
                        "A Spigot test base URI is already active.");
            }
            scopedSpigotTestBaseUrl = normalized;
        }
        return new ScopedSpigotTestBaseUrl(normalized);
    }

    private static String spigotUpdateBaseUrl() {
        String testBaseUrl = scopedSpigotTestBaseUrl;
        if (testBaseUrl != null) return testBaseUrl;
        PRODUCTION_SPIGOT_ORIGIN_SELECTIONS.incrementAndGet();
        return SPIGOT_UPDATE_BASE_URL;
    }

    static int productionOriginSelectionsForTests() {
        return PRODUCTION_SPIGOT_ORIGIN_SELECTIONS.get();
    }

    private static String normalizeLoopbackTestOrigin(URI baseUri) {
        if (baseUri == null) {
            throw new IllegalArgumentException("Test base URI cannot be null.");
        }
        String scheme = baseUri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) &&
                !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                    "Test base URI must use HTTP or HTTPS.");
        }
        String host = baseUri.getHost();
        if (host == null || host.isBlank() || !isLoopbackTestHost(host)) {
            throw new IllegalArgumentException(
                    "Test base URI must have a loopback host.");
        }
        if (baseUri.getRawUserInfo() != null ||
                baseUri.getRawQuery() != null ||
                baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "Test base URI cannot contain user info, a query, or a fragment.");
        }
        String path = baseUri.getRawPath();
        if (path != null && !path.isBlank() && !"/".equals(path)) {
            throw new IllegalArgumentException(
                    "Test base URI cannot contain an API path.");
        }
        String normalized = baseUri.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isLoopbackTestHost(String host) {
        String normalizedHost = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        if ("localhost".equalsIgnoreCase(normalizedHost) ||
                "::1".equals(normalizedHost) ||
                "0:0:0:0:0:0:0:1".equals(normalizedHost)) {
            return true;
        }
        String[] octets = normalizedHost.split("\\.", -1);
        if (octets.length != 4 || !"127".equals(octets[0])) return false;
        for (String octet : octets) {
            if (octet.isBlank()) return false;
            try {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) return false;
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return true;
    }

    static final class ScopedSpigotTestBaseUrl implements AutoCloseable {
        private final String expectedBaseUrl;
        private boolean closed;

        private ScopedSpigotTestBaseUrl(String expectedBaseUrl) {
            this.expectedBaseUrl = expectedBaseUrl;
        }

        @Override
        public void close() {
            synchronized (SPIGOT_TEST_BASE_URL_LOCK) {
                if (closed) return;
                if (!expectedBaseUrl.equals(scopedSpigotTestBaseUrl)) {
                    throw new IllegalStateException(
                            "Spigot test base URI scope changed unexpectedly.");
                }
                scopedSpigotTestBaseUrl = null;
                closed = true;
            }
        }
    }

    private static long currentGeneration(JavaPlugin plugin) {
        synchronized (PLUGIN_LIFECYCLE_LOCK) {
            return currentGenerationLocked(plugin);
        }
    }

    static long lifecycleGeneration(JavaPlugin plugin) {
        return currentGeneration(plugin);
    }

    private static long currentGenerationLocked(JavaPlugin plugin) {
        return PLUGIN_GENERATIONS.getOrDefault(plugin, 0L);
    }

    private static boolean isCurrentGeneration(JavaPlugin plugin, long generation) {
        synchronized (PLUGIN_LIFECYCLE_LOCK) {
            return currentGenerationLocked(plugin) == generation;
        }
    }

    static boolean isLifecycleCurrent(
            JavaPlugin plugin, long generation) {
        return isCurrentGeneration(plugin, generation);
    }

    static boolean publishIfLifecycleCurrent(
            JavaPlugin plugin,
            long generation,
            LifecyclePublication publication) throws IOException {
        synchronized (PLUGIN_LIFECYCLE_LOCK) {
            if (currentGenerationLocked(plugin) != generation) {
                return false;
            }
            publication.publish();
            return true;
        }
    }

    static NightbreakPluginAsyncWorkTracker.Work registerAsyncWork(
            JavaPlugin plugin,
            long generation) {
        synchronized (PLUGIN_LIFECYCLE_LOCK) {
            if (currentGenerationLocked(plugin) != generation) return null;
            return ASYNC_WORK.register(plugin);
        }
    }

    private static void notifyPluginUpdateDownloaded(JavaPlugin plugin,
                                                     long generation,
                                                     PluginUpdateDownload result) {
        List<PluginUpdateListenerRegistration> registrations;
        synchronized (PLUGIN_LIFECYCLE_LOCK) {
            if (currentGenerationLocked(plugin) != generation) return;
            CopyOnWriteArrayList<PluginUpdateListenerRegistration> current =
                    PLUGIN_UPDATE_LISTENERS.get(plugin);
            if (current == null || current.isEmpty()) return;
            registrations = new ArrayList<>(current);
        }

        for (PluginUpdateListenerRegistration registration : registrations) {
            if (registration.isClosed()) continue;
            try {
                registration.listener.accept(result);
            } catch (Exception exception) {
                plugin.getLogger().warning("Plugin update download listener failed: " + exception.getMessage());
            }
        }
    }

    private static void removeListener(PluginUpdateListenerRegistration registration) {
        synchronized (PLUGIN_LIFECYCLE_LOCK) {
            CopyOnWriteArrayList<PluginUpdateListenerRegistration> registrations =
                    PLUGIN_UPDATE_LISTENERS.get(registration.plugin);
            if (registrations == null) return;
            registrations.remove(registration);
            if (registrations.isEmpty()) {
                PLUGIN_UPDATE_LISTENERS.remove(registration.plugin);
            }
        }
    }

    private static void cancelTasksForPlugin(Map<String, Integer> tasks, String keyPrefix) {
        for (Map.Entry<String, Integer> entry : new ArrayList<>(tasks.entrySet())) {
            if (!entry.getKey().startsWith(keyPrefix)) continue;
            if (tasks.remove(entry.getKey(), entry.getValue())) {
                cancelTask(entry.getValue());
            }
        }
    }

    private static void cancelReplacedTask(Integer previousTaskId, int replacementTaskId) {
        if (previousTaskId != null && previousTaskId != replacementTaskId) {
            cancelTask(previousTaskId);
        }
    }

    private static void cancelTask(int taskId) {
        if (Bukkit.getServer() == null) return;
        try {
            Bukkit.getScheduler().cancelTask(taskId);
        } catch (RuntimeException ignored) {
            // The server may already be shutting down and have discarded its scheduler.
        }
    }

    private static final class PluginUpdateListenerRegistration implements ListenerRegistration {
        private final JavaPlugin plugin;
        private final Consumer<PluginUpdateDownload> listener;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private PluginUpdateListenerRegistration(JavaPlugin plugin,
                                                 Consumer<PluginUpdateDownload> listener) {
            this.plugin = plugin;
            this.listener = listener;
        }

        @Override
        public void close() {
            synchronized (PLUGIN_LIFECYCLE_LOCK) {
                if (!closed.compareAndSet(false, true)) return;
                removeListener(this);
            }
        }

        private void markClosedFromShutdown() {
            closed.set(true);
        }

        private boolean isClosed() {
            return closed.get();
        }
    }

    @FunctionalInterface
    interface LifecyclePublication {
        void publish() throws IOException;
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
