package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class NightbreakBulkDownloader {
    private NightbreakBulkDownloader() {
    }

    public static <T extends NightbreakManagedContent> void execute(JavaPlugin plugin,
                                                                    String pluginDisplayName,
                                                                    CommandSender sender,
                                                                    List<T> allPackages,
                                                                    boolean updatesOnly,
                                                                    AtomicBoolean guard,
                                                                    Consumer<CommandSender> reloadAction) {
        if (!NightbreakAccount.hasToken()) {
            sender.sendMessage(ChatColorConverter.convert("&c[" + pluginDisplayName + "] No Nightbreak token registered. Use /nightbreaklogin <token> first."));
            return;
        }

        if (!guard.compareAndSet(false, true)) {
            sender.sendMessage(ChatColorConverter.convert("&c[" + pluginDisplayName + "] A bulk download is already in progress! Please wait for it to finish."));
            return;
        }

        NightbreakContentRefresher.refreshAsync(plugin, allPackages, content -> true, outdated -> {
            List<T> downloadable = collectPackages(allPackages, updatesOnly);

            if (downloadable.isEmpty()) {
                guard.set(false);
                if (updatesOnly) {
                    sender.sendMessage(ChatColorConverter.convert("&a[" + pluginDisplayName + "] No content updates are currently pending."));
                } else if (hasAnySlug(allPackages)) {
                    sender.sendMessage(ChatColorConverter.convert("&a[" + pluginDisplayName + "] No new content to download! All available packages are already downloaded."));
                } else {
                    sender.sendMessage(ChatColorConverter.convert("&c[" + pluginDisplayName + "] No accessible content found. Link your Nightbreak account and ensure you have access."));
                }
                return;
            }

            sender.sendMessage(ChatColorConverter.convert("&e[" + pluginDisplayName + "] Found " + downloadable.size() + " packages to " + (updatesOnly ? "update" : "download") + ". Starting..."));

            Player player = sender instanceof Player playerSender ? playerSender : null;
            File importsFolder = new File(plugin.getDataFolder(), "imports");
            if (!importsFolder.exists()) {
                importsFolder.mkdirs();
            }

            downloadNext(plugin, pluginDisplayName, downloadable, 0, importsFolder, sender, player,
                    new AtomicInteger(), new AtomicInteger(), new ArrayList<>(), guard, reloadAction, updatesOnly);
        });
    }

    private static <T extends NightbreakManagedContent> List<T> collectPackages(List<T> packages, boolean updatesOnly) {
        List<T> downloadable = new ArrayList<>();
        Set<String> seenSlugs = new HashSet<>();
        for (T pkg : packages) {
            String slug = pkg.getNightbreakSlug();
            if (slug == null || slug.isEmpty()) continue;
            if (!seenSlugs.add(slug)) continue;
            if (updatesOnly) {
                if (!pkg.isOutOfDate()) continue;
                if (pkg.getCachedAccessInfo() != null && !pkg.getCachedAccessInfo().hasAccess) continue;
                downloadable.add(pkg);
            } else if ((!pkg.isDownloaded() || pkg.isOutOfDate())
                    && (pkg.getCachedAccessInfo() == null || pkg.getCachedAccessInfo().hasAccess)) {
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
                                                                          AtomicBoolean guard,
                                                                          Consumer<CommandSender> reloadAction,
                                                                          boolean updatesOnly) {
        if (player != null && !player.isOnline()) {
            if (index >= packages.size()) {
                guard.set(false);
                if (completed.get() > 0) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> reloadAction.accept(Bukkit.getConsoleSender()), 20L);
                }
                return;
            }
            T pkg = packages.get(index);
            NightbreakContentManager.downloadAsync(plugin, pkg.getNightbreakSlug(), importsFolder, null, success -> {
                if (success) {
                    pkg.enableAfterDownload().whenComplete((ignored, throwable) ->
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (throwable == null) {
                                    completed.incrementAndGet();
                                } else {
                                    throwable.printStackTrace();
                                    failed.incrementAndGet();
                                    failedNames.add(pkg.getDisplayName());
                                }
                                downloadNext(plugin, pluginDisplayName, packages, index + 1, importsFolder, sender, player,
                                        completed, failed, failedNames, guard, reloadAction, updatesOnly);
                            }));
                } else {
                    failed.incrementAndGet();
                    failedNames.add(pkg.getDisplayName());
                    downloadNext(plugin, pluginDisplayName, packages, index + 1, importsFolder, sender, player,
                            completed, failed, failedNames, guard, reloadAction, updatesOnly);
                }
            });
            return;
        }

        if (index >= packages.size()) {
            guard.set(false);
            String operation = updatesOnly ? "Updates finished" : "Bulk download finished";
            sender.sendMessage(ChatColorConverter.convert("&a[" + pluginDisplayName + "] " + operation + "! Downloaded/updated: " + completed.get() + ", Failed: " + failed.get()));
            if (!failedNames.isEmpty()) {
                sender.sendMessage(ChatColorConverter.convert("&c[" + pluginDisplayName + "] Failed packages: " + String.join(", ", failedNames)));
            }
            if (completed.get() > 0) {
                sender.sendMessage(ChatColorConverter.convert("&a[" + pluginDisplayName + "] Reloading to apply downloads..."));
                Bukkit.getScheduler().runTaskLater(plugin, () -> reloadAction.accept(sender), 20L);
            }
            return;
        }

        T pkg = packages.get(index);
        sender.sendMessage(ChatColorConverter.convert("&7[" + pluginDisplayName + "] (" + (index + 1) + "/" + packages.size() + ") Downloading: " + pkg.getDisplayName() + "..."));
        NightbreakContentManager.downloadAsync(plugin, pkg.getNightbreakSlug(), importsFolder, null, success -> {
            if (success) {
                pkg.enableAfterDownload().whenComplete((ignored, throwable) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (throwable == null) {
                                completed.incrementAndGet();
                                int remaining = packages.size() - (index + 1);
                                if (remaining > 0) {
                                    sender.sendMessage(ChatColorConverter.convert("&a[" + pluginDisplayName + "] Downloaded " + pkg.getDisplayName() + "! &7Please hold on, " + remaining + " more to go..."));
                                } else {
                                    sender.sendMessage(ChatColorConverter.convert("&a[" + pluginDisplayName + "] Downloaded " + pkg.getDisplayName() + "!"));
                                }
                            } else {
                                throwable.printStackTrace();
                                failed.incrementAndGet();
                                failedNames.add(pkg.getDisplayName());
                                sender.sendMessage(ChatColorConverter.convert("&c[" + pluginDisplayName + "] Failed to prepare: " + pkg.getDisplayName()));
                            }
                            downloadNext(plugin, pluginDisplayName, packages, index + 1, importsFolder, sender, player,
                                    completed, failed, failedNames, guard, reloadAction, updatesOnly);
                        }));
            } else {
                failed.incrementAndGet();
                failedNames.add(pkg.getDisplayName());
                sender.sendMessage(ChatColorConverter.convert("&c[" + pluginDisplayName + "] Failed to download: " + pkg.getDisplayName()));
                downloadNext(plugin, pluginDisplayName, packages, index + 1, importsFolder, sender, player,
                        completed, failed, failedNames, guard, reloadAction, updatesOnly);
            }
        });
    }
}
