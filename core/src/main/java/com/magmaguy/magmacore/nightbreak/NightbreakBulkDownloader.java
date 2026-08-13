package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class NightbreakBulkDownloader {
    private static final Object LIFECYCLE_LOCK = new Object();
    private static final Map<JavaPlugin, Long> PLUGIN_GENERATIONS = new IdentityHashMap<>();

    private NightbreakBulkDownloader() {
    }

    private enum DownloadMode {
        INSTALL,
        UPDATE,
        FORCE_REINSTALL
    }

    /**
     * Invalidates every in-flight bulk operation owned by this exact plugin
     * instance. This must be called from plugin shutdown because direct
     * onDisable/onEnable reloads do not make Bukkit cancel the old callbacks.
     */
    public static void shutdown(JavaPlugin plugin) {
        if (plugin == null) return;
        synchronized (LIFECYCLE_LOCK) {
            PLUGIN_GENERATIONS.put(plugin, currentGenerationLocked(plugin) + 1L);
        }
    }

    public static <T extends NightbreakManagedContent> void execute(JavaPlugin plugin,
                                                                    String pluginDisplayName,
                                                                    CommandSender sender,
                                                                    List<T> allPackages,
                                                                    boolean updatesOnly,
                                                                    AtomicBoolean guard,
                                                                    Consumer<CommandSender> reloadAction) {
        execute(plugin, pluginDisplayName, sender, allPackages, updatesOnly, guard, reloadAction, true);
    }

    public static <T extends NightbreakManagedContent> void execute(JavaPlugin plugin,
                                                                    String pluginDisplayName,
                                                                    CommandSender sender,
                                                                    List<T> allPackages,
                                                                    boolean updatesOnly,
                                                                    AtomicBoolean guard,
                                                                    Consumer<CommandSender> reloadAction,
                                                                    boolean reloadAfterDownloads) {
        execute(plugin,
                pluginDisplayName,
                sender,
                allPackages,
                updatesOnly ? DownloadMode.UPDATE : DownloadMode.INSTALL,
                guard,
                reloadAction,
                reloadAfterDownloads);
    }

    public static <T extends NightbreakManagedContent> void executeForceReinstall(
            JavaPlugin plugin,
            String pluginDisplayName,
            CommandSender sender,
            List<T> packages,
            AtomicBoolean guard,
            Consumer<CommandSender> reloadAction) {
        execute(plugin,
                pluginDisplayName,
                sender,
                packages,
                DownloadMode.FORCE_REINSTALL,
                guard,
                reloadAction,
                true);
    }

    private static <T extends NightbreakManagedContent> void execute(
            JavaPlugin plugin,
            String pluginDisplayName,
            CommandSender sender,
            List<T> allPackages,
            DownloadMode mode,
            AtomicBoolean guard,
            Consumer<CommandSender> reloadAction,
            boolean reloadAfterDownloads) {
        if (!NightbreakAccount.hasToken()) {
            sender.sendMessage(ChatColorConverter.convert("&c[" + pluginDisplayName + "] No account token registered. Use /nightbreaklogin <token> first."));
            return;
        }
        if (NightbreakAccount.hasAuthFailure()) {
            NightbreakSetupMenuHelper.sendTokenUpdatePrompt(sender, pluginDisplayName);
            return;
        }

        if (!guard.compareAndSet(false, true)) {
            sender.sendMessage(ChatColorConverter.convert("&c[" + pluginDisplayName + "] A bulk download is already in progress! Please wait for it to finish."));
            return;
        }
        LifecycleLease lease = captureLease(plugin, guard);

        if (mode == DownloadMode.UPDATE) {
            try {
                NightbreakContentRefresher.refreshAsync(
                        plugin,
                        allPackages,
                        content -> true,
                        lease::isCurrent,
                        outdated -> beginDownloads(
                                plugin,
                                pluginDisplayName,
                                sender,
                                allPackages,
                                DownloadMode.UPDATE,
                                lease,
                                reloadAction,
                                reloadAfterDownloads),
                        lease::release);
            } catch (RuntimeException exception) {
                lease.release();
                throw exception;
            }
            return;
        }

        // Full installs do not need a second catalog/access refresh. Every
        // download performs its own cached access check, and products such as
        // EliteMobs already prefetch access during startup. Rechecking every
        // slug here made the player-facing command appear to hang for minutes.
        beginDownloads(
                plugin,
                pluginDisplayName,
                sender,
                allPackages,
                mode,
                lease,
                reloadAction,
                reloadAfterDownloads);
    }

    private static <T extends NightbreakManagedContent> void beginDownloads(
            JavaPlugin plugin,
            String pluginDisplayName,
            CommandSender sender,
            List<T> allPackages,
            DownloadMode mode,
            LifecycleLease lease,
            Consumer<CommandSender> reloadAction,
            boolean reloadAfterDownloads) {
        if (!lease.isCurrent()) {
            lease.release();
            return;
        }
        if (NightbreakAccount.hasAuthFailure()) {
            lease.release();
            NightbreakSetupMenuHelper.sendTokenUpdatePrompt(sender, pluginDisplayName);
            return;
        }

        List<T> downloadable = collectPackages(allPackages, mode);
        logSelection(pluginDisplayName, allPackages, downloadable, mode);

        if (downloadable.isEmpty()) {
            lease.release();
            if (mode == DownloadMode.UPDATE) {
                sender.sendMessage(ChatColorConverter.convert("&a[" + pluginDisplayName + "] No content updates are currently pending."));
            } else if (mode == DownloadMode.FORCE_REINSTALL) {
                sender.sendMessage(ChatColorConverter.convert("&c[" + pluginDisplayName + "] No Nightbreak-managed content matched the reinstall request."));
            } else if (hasAnySlug(allPackages)) {
                sender.sendMessage(ChatColorConverter.convert("&a[" + pluginDisplayName + "] No new content to download! All available packages are already downloaded."));
            } else {
                sender.sendMessage(ChatColorConverter.convert("&c[" + pluginDisplayName + "] No accessible content found. Connect this server and ensure you have access."));
            }
            return;
        }

        String action = switch (mode) {
            case INSTALL -> "download";
            case UPDATE -> "update";
            case FORCE_REINSTALL -> "force reinstall";
        };
        sender.sendMessage(ChatColorConverter.convert("&e[" + pluginDisplayName + "] Found " + downloadable.size() + " packages to " + action + ". Starting..."));

        Player player = sender instanceof Player playerSender ? playerSender : null;
        File importsFolder = new File(plugin.getDataFolder(), "imports");
        if (!importsFolder.exists()) {
            importsFolder.mkdirs();
        }

        downloadNext(plugin, pluginDisplayName, downloadable, 0, importsFolder, sender, player,
                new AtomicInteger(), new AtomicInteger(), new ArrayList<>(), lease, reloadAction, mode, reloadAfterDownloads);
    }

    private static <T extends NightbreakManagedContent> void logSelection(
            String pluginDisplayName,
            List<T> packages,
            List<T> selected,
            DownloadMode mode) {
        int withSlug = 0;
        int downloaded = 0;
        int installed = 0;
        int outdated = 0;
        int accessAllowed = 0;
        int accessDenied = 0;
        int accessUnknown = 0;
        for (T pkg : packages) {
            if (pkg.getNightbreakSlug() != null
                    && !pkg.getNightbreakSlug().isEmpty()) {
                withSlug++;
            }
            if (pkg.isDownloaded()) downloaded++;
            if (pkg.isInstalled()) installed++;
            if (pkg.isOutOfDate()) outdated++;
            if (pkg.getCachedAccessInfo() == null) {
                accessUnknown++;
            } else if (pkg.getCachedAccessInfo().hasAccess) {
                accessAllowed++;
            } else {
                accessDenied++;
            }
        }
        Logger.info(
                "[" + pluginDisplayName + "] Nightbreak bulk selection: mode=" +
                        mode.name().toLowerCase(java.util.Locale.ROOT) +
                        ", total=" + packages.size() +
                        ", withSlug=" + withSlug +
                        ", downloaded=" + downloaded +
                        ", installed=" + installed +
                        ", outdated=" + outdated +
                        ", accessAllowed=" + accessAllowed +
                        ", accessDenied=" + accessDenied +
                        ", accessUnknown=" + accessUnknown +
                        ", selected=" + selected.size() + ".");
    }

    static <T extends NightbreakManagedContent> List<T> collectPackages(List<T> packages, boolean updatesOnly) {
        return collectPackages(packages, updatesOnly ? DownloadMode.UPDATE : DownloadMode.INSTALL);
    }

    private static <T extends NightbreakManagedContent> List<T> collectPackages(List<T> packages,
                                                                                DownloadMode mode) {
        List<T> downloadable = new ArrayList<>();
        Set<String> seenSlugs = new HashSet<>();
        for (T pkg : packages) {
            String slug = pkg.getNightbreakSlug();
            if (slug == null || slug.isEmpty()) continue;
            if (!seenSlugs.add(slug)) continue;
            if (mode == DownloadMode.FORCE_REINSTALL) {
                downloadable.add(pkg);
            } else if (mode == DownloadMode.UPDATE) {
                if (!pkg.isOutOfDate()) continue;
                if (pkg.getCachedAccessInfo() != null && !pkg.getCachedAccessInfo().hasAccess) continue;
                downloadable.add(pkg);
            } else if (!pkg.isDownloaded() || pkg.isOutOfDate()) {
                // Cached access is advisory UI state. A requested install must
                // reach the authenticated download check, which is the
                // authoritative decision and may be newer than this cache.
                downloadable.add(pkg);
            }
        }
        return downloadable;
    }

    private static <T extends NightbreakManagedContent> boolean hasAnySlug(List<T> packages) {
        for (T pkg : packages) {
            String slug = pkg.getNightbreakSlug();
            if (slug != null && !slug.isEmpty()) return true;
        }
        return false;
    }

    private static <T extends NightbreakManagedContent> void downloadNext(JavaPlugin plugin,
                                                                          String pluginDisplayName,
                                                                          List<T> packages,
                                                                          int index,
                                                                          File importsFolder,
                                                                          CommandSender sender,
                                                                          Player player,
                                                                          AtomicInteger completed,
                                                                          AtomicInteger failed,
                                                                          List<String> failedNames,
                                                                          LifecycleLease lease,
                                                                          Consumer<CommandSender> reloadAction,
                                                                          DownloadMode mode,
                                                                          boolean reloadAfterDownloads) {
        if (!lease.isCurrent()) {
            lease.release();
            return;
        }

        boolean playerUnavailable = player != null && !player.isOnline();
        CommandSender activeSender = playerUnavailable ? Bukkit.getConsoleSender() : sender;
        if (index >= packages.size()) {
            finishDownloads(plugin, pluginDisplayName, activeSender, completed, failed,
                    failedNames, lease, reloadAction, mode, reloadAfterDownloads);
            return;
        }

        T pkg = packages.get(index);
        if (!playerUnavailable) {
            sender.sendMessage(ChatColorConverter.convert("&7[" + pluginDisplayName + "] (" +
                    (index + 1) + "/" + packages.size() + ") Downloading: " + pkg.getDisplayName() + "..."));
        }
        NightbreakContentManager.downloadAsync(
                plugin,
                pkg.getNightbreakSlug(),
                importsFolder,
                null,
                lease,
                success -> {
                    if (!lease.isCurrent()) {
                        lease.release();
                        return;
                    }
                    if (!success) {
                        if (abortIfAuthFailure(pluginDisplayName, activeSender, player, lease)) return;
                        failed.incrementAndGet();
                        failedNames.add(pkg.getDisplayName());
                        if (!playerUnavailable) {
                            sender.sendMessage(ChatColorConverter.convert("&c[" + pluginDisplayName + "] Failed to download: " + pkg.getDisplayName()));
                        }
                        downloadNext(plugin, pluginDisplayName, packages, index + 1, importsFolder, sender, player,
                                completed, failed, failedNames, lease, reloadAction, mode, reloadAfterDownloads);
                        return;
                    }

                    if (!lease.isCurrent()) {
                        lease.release();
                        return;
                    }
                    CompletableFuture<Void> preparation = mode == DownloadMode.FORCE_REINSTALL
                            ? CompletableFuture.completedFuture(null)
                            : pkg.enableAfterDownload();
                    preparation.whenComplete((ignored, throwable) -> {
                        if (!lease.isCurrent()) {
                            lease.release();
                            return;
                        }
                        lease.runSync(() -> {
                            if (throwable == null) {
                                completed.incrementAndGet();
                                if (!playerUnavailable) {
                                    int remaining = packages.size() - (index + 1);
                                    String suffix = remaining > 0
                                            ? "! &7Please hold on, " + remaining + " more to go..."
                                            : "!";
                                    String verb = mode == DownloadMode.FORCE_REINSTALL ? "Reinstalled " : "Downloaded ";
                                    sender.sendMessage(ChatColorConverter.convert("&a[" + pluginDisplayName + "] " + verb + pkg.getDisplayName() + suffix));
                                }
                            } else {
                                throwable.printStackTrace();
                                failed.incrementAndGet();
                                failedNames.add(pkg.getDisplayName());
                                if (!playerUnavailable) {
                                    sender.sendMessage(ChatColorConverter.convert("&c[" + pluginDisplayName + "] Failed to prepare: " + pkg.getDisplayName()));
                                }
                            }
                            downloadNext(plugin, pluginDisplayName, packages, index + 1, importsFolder, sender, player,
                                    completed, failed, failedNames, lease, reloadAction, mode, reloadAfterDownloads);
                        });
                    });
                });
    }

    private static void finishDownloads(JavaPlugin plugin,
                                        String pluginDisplayName,
                                        CommandSender sender,
                                        AtomicInteger completed,
                                        AtomicInteger failed,
                                        List<String> failedNames,
                                        LifecycleLease lease,
                                        Consumer<CommandSender> reloadAction,
                                        DownloadMode mode,
                                        boolean reloadAfterDownloads) {
        if (!lease.isCurrent()) {
            lease.release();
            return;
        }

        String operation = switch (mode) {
            case INSTALL -> "Bulk download finished";
            case UPDATE -> "Updates finished";
            case FORCE_REINSTALL -> "Force reinstall finished";
        };
        String completedLabel = mode == DownloadMode.FORCE_REINSTALL ? "Reinstalled" : "Downloaded/updated";
        sender.sendMessage(ChatColorConverter.convert("&a[" + pluginDisplayName + "] " + operation +
                "! " + completedLabel + ": " + completed.get() + ", Failed: " + failed.get()));
        if (!failedNames.isEmpty()) {
            sender.sendMessage(ChatColorConverter.convert("&c[" + pluginDisplayName + "] Failed packages: " + String.join(", ", failedNames)));
        }
        if (completed.get() > 0 && reloadAfterDownloads) {
            sender.sendMessage(ChatColorConverter.convert("&a[" + pluginDisplayName + "] Reloading to apply downloads..."));
            // Keep the operation guard until the reload begins so a second
            // bulk command cannot start during this delayed handoff window.
            lease.runLater(() -> {
                try {
                    reloadAction.accept(sender);
                } finally {
                    lease.release();
                }
            }, 20L);
        } else if (completed.get() > 0) {
            sender.sendMessage(ChatColorConverter.convert("&a[" + pluginDisplayName + "] Downloaded content updates. Restart the server to use them."));
            lease.release();
        } else {
            lease.release();
        }
    }

    private static boolean abortIfAuthFailure(String pluginDisplayName,
                                              CommandSender sender,
                                              Player player,
                                              LifecycleLease lease) {
        if (!NightbreakAccount.hasAuthFailure()) return false;
        lease.release();
        CommandSender target = player != null && !player.isOnline() ? Bukkit.getConsoleSender() : sender;
        NightbreakSetupMenuHelper.sendTokenUpdatePrompt(target, pluginDisplayName);
        return true;
    }

    private static LifecycleLease captureLease(JavaPlugin plugin, AtomicBoolean guard) {
        synchronized (LIFECYCLE_LOCK) {
            return new LifecycleLease(plugin, currentGenerationLocked(plugin), guard);
        }
    }

    static NightbreakContentManager.OperationGate captureOperationGate(JavaPlugin plugin) {
        synchronized (LIFECYCLE_LOCK) {
            return new LifecycleLease(plugin, currentGenerationLocked(plugin), null);
        }
    }

    private static long currentGenerationLocked(JavaPlugin plugin) {
        return PLUGIN_GENERATIONS.getOrDefault(plugin, 0L);
    }

    private static final class LifecycleLease implements NightbreakContentManager.OperationGate {
        private final JavaPlugin plugin;
        private final long generation;
        private final AtomicBoolean guard;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private LifecycleLease(JavaPlugin plugin, long generation, AtomicBoolean guard) {
            this.plugin = plugin;
            this.generation = generation;
            this.guard = guard;
        }

        @Override
        public boolean isCurrent() {
            synchronized (LIFECYCLE_LOCK) {
                return currentGenerationLocked(plugin) == generation;
            }
        }

        @Override
        public boolean publish(Path temporaryFile, Path destinationFile) throws IOException {
            synchronized (LIFECYCLE_LOCK) {
                if (currentGenerationLocked(plugin) != generation) return false;
                try {
                    Files.move(temporaryFile, destinationFile,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporaryFile, destinationFile, StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            }
        }

        private void runSync(Runnable action) {
            if (!isCurrent()) {
                release();
                return;
            }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (isCurrent()) action.run();
                    else release();
                });
            } catch (RuntimeException exception) {
                release();
            }
        }

        private void runLater(Runnable action, long delayTicks) {
            if (!isCurrent()) {
                release();
                return;
            }
            try {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (isCurrent()) action.run();
                    else release();
                }, delayTicks);
            } catch (RuntimeException ignored) {
                // Plugin shutdown invalidates the lease; there is nothing left
                // to apply in this lifecycle.
                release();
            }
        }

        private void release() {
            if (released.compareAndSet(false, true) && guard != null) guard.set(false);
        }
    }
}
