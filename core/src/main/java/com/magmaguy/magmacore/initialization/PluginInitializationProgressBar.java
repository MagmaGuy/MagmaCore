package com.magmaguy.magmacore.initialization;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PluginInitializationProgressBar {
    private static final Map<String, ProgressBarData> progressBars = new ConcurrentHashMap<>();

    private PluginInitializationProgressBar() {
    }

    public static void start(JavaPlugin plugin, String displayName, String permission, int totalSteps) {
        install(new ProgressBarData(
                plugin,
                null,
                0,
                displayName,
                permission,
                totalSteps), true);
    }

    static void start(JavaPlugin plugin,
                      String ownerToken,
                      long generation,
                      String displayName,
                      String permission,
                      int totalSteps) {
        install(new ProgressBarData(
                plugin,
                ownerToken,
                generation,
                displayName,
                permission,
                totalSteps), false);
    }

    private static void install(ProgressBarData replacement, boolean force) {
        String pluginName = replacement.plugin.getName();
        ProgressBarData previous;
        while (true) {
            previous = progressBars.get(pluginName);
            if (!force
                    && previous != null
                    && previous.generation > replacement.generation) {
                return;
            }

            boolean installed = previous == null
                    ? progressBars.putIfAbsent(pluginName, replacement) == null
                    : progressBars.replace(pluginName, previous, replacement);
            if (installed) {
                break;
            }
        }

        if (previous != null) {
            previous.complete();
        }
        replacement.start();
    }

    public static void step(JavaPlugin plugin, String description) {
        ProgressBarData progressBarData = progressBars.get(plugin.getName());
        if (progressBarData == null) return;
        progressBarData.step(description);
    }

    static void step(JavaPlugin plugin, String ownerToken, String description) {
        ProgressBarData progressBarData = progressBars.get(plugin.getName());
        if (progressBarData == null || !progressBarData.isOwnedBy(ownerToken)) return;
        progressBarData.step(description);
    }

    public static void status(JavaPlugin plugin, String description) {
        ProgressBarData progressBarData = progressBars.get(plugin.getName());
        if (progressBarData == null) return;
        progressBarData.status(description);
    }

    static void status(JavaPlugin plugin, String ownerToken, String description) {
        ProgressBarData progressBarData = progressBars.get(plugin.getName());
        if (progressBarData == null || !progressBarData.isOwnedBy(ownerToken)) return;
        progressBarData.status(description);
    }

    public static void complete(JavaPlugin plugin) {
        ProgressBarData progressBarData = progressBars.remove(plugin.getName());
        if (progressBarData == null) return;
        progressBarData.complete();
    }

    static void complete(JavaPlugin plugin, String ownerToken) {
        ProgressBarData progressBarData = progressBars.get(plugin.getName());
        if (progressBarData == null || !progressBarData.isOwnedBy(ownerToken)) return;
        if (progressBars.remove(plugin.getName(), progressBarData)) {
            progressBarData.complete();
        }
    }

    private static class ProgressBarData {
        private final JavaPlugin plugin;
        private final String ownerToken;
        private final long generation;
        private final String displayName;
        private final String permission;
        private final int totalSteps;
        private int currentStep;
        private BossBar bossBar;
        private Listener listener;
        private boolean completed;

        private ProgressBarData(JavaPlugin plugin,
                                String ownerToken,
                                long generation,
                                String displayName,
                                String permission,
                                int totalSteps) {
            this.plugin = plugin;
            this.ownerToken = ownerToken;
            this.generation = generation;
            this.displayName = displayName;
            this.permission = permission;
            this.totalSteps = totalSteps;
        }

        private synchronized void start() {
            if (completed) return;
            currentStep = 0;
            bossBar = Bukkit.createBossBar(
                    ChatColor.GREEN + "" + ChatColor.BOLD + displayName + ChatColor.WHITE + " ▸ Initializing...",
                    BarColor.GREEN,
                    BarStyle.SEGMENTED_10);
            bossBar.setProgress(0);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (canView(player)) {
                    bossBar.addPlayer(player);
                }
            }
            listener = new Listener() {
                @EventHandler
                public void onPlayerJoin(PlayerJoinEvent event) {
                    if (bossBar != null && canView(event.getPlayer())) {
                        bossBar.addPlayer(event.getPlayer());
                    }
                }
            };
            Bukkit.getPluginManager().registerEvents(listener, plugin);
        }

        private synchronized void step(String description) {
            if (completed) return;
            currentStep++;
            double progress = totalSteps <= 0 ? 1.0 : Math.min((double) currentStep / totalSteps, 1.0);
            update(description, progress);
        }

        private synchronized void status(String description) {
            if (completed) return;
            double progress = totalSteps <= 0 ? 0 : Math.min((double) currentStep / totalSteps, 1.0);
            update(description, progress);
        }

        private void update(String description, double progress) {
            Runnable task = () -> {
                synchronized (ProgressBarData.this) {
                    if (completed || bossBar == null) return;
                    bossBar.setTitle(ChatColor.GREEN + "" + ChatColor.BOLD + displayName + ChatColor.WHITE + " ▸ " + ChatColor.YELLOW + description);
                    bossBar.setProgress(progress);
                }
            };
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        }

        private synchronized void complete() {
            if (completed) return;
            completed = true;
            if (bossBar != null) {
                bossBar.removeAll();
                bossBar = null;
            }
            if (listener != null) {
                HandlerList.unregisterAll(listener);
                listener = null;
            }
        }

        private boolean canView(Player player) {
            if (permission == null || permission.isBlank()) {
                return player.isOp();
            }
            return player.hasPermission(permission);
        }

        private boolean isOwnedBy(String candidateOwnerToken) {
            return ownerToken != null && ownerToken.equals(candidateOwnerToken);
        }
    }
}
