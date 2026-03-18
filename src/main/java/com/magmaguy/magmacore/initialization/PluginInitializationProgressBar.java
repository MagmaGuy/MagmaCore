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
        complete(plugin);
        ProgressBarData progressBarData = new ProgressBarData(plugin, displayName, permission, totalSteps);
        progressBars.put(plugin.getName(), progressBarData);
        progressBarData.start();
    }

    public static void step(JavaPlugin plugin, String description) {
        ProgressBarData progressBarData = progressBars.get(plugin.getName());
        if (progressBarData == null) return;
        progressBarData.step(description);
    }

    public static void status(JavaPlugin plugin, String description) {
        ProgressBarData progressBarData = progressBars.get(plugin.getName());
        if (progressBarData == null) return;
        progressBarData.status(description);
    }

    public static void complete(JavaPlugin plugin) {
        ProgressBarData progressBarData = progressBars.remove(plugin.getName());
        if (progressBarData == null) return;
        progressBarData.complete();
    }

    private static class ProgressBarData {
        private final JavaPlugin plugin;
        private final String displayName;
        private final String permission;
        private final int totalSteps;
        private int currentStep;
        private BossBar bossBar;
        private Listener listener;

        private ProgressBarData(JavaPlugin plugin, String displayName, String permission, int totalSteps) {
            this.plugin = plugin;
            this.displayName = displayName;
            this.permission = permission;
            this.totalSteps = totalSteps;
        }

        private void start() {
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

        private void step(String description) {
            currentStep++;
            double progress = totalSteps <= 0 ? 1.0 : Math.min((double) currentStep / totalSteps, 1.0);
            update(description, progress);
        }

        private void status(String description) {
            double progress = totalSteps <= 0 ? 0 : Math.min((double) currentStep / totalSteps, 1.0);
            update(description, progress);
        }

        private void update(String description, double progress) {
            Runnable task = () -> {
                if (bossBar == null) return;
                bossBar.setTitle(ChatColor.GREEN + "" + ChatColor.BOLD + displayName + ChatColor.WHITE + " ▸ " + ChatColor.YELLOW + description);
                bossBar.setProgress(progress);
            };
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        }

        private void complete() {
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
    }
}
