package com.magmaguy.magmacore.util;

import com.magmaguy.easyminecraftgoals.NMSManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ScoreboardUtil {
    private static final String DEFAULT_SIDEBAR_OBJECTIVE = "mcore_sb";
    private static final int MAX_SIDEBAR_LINES = 15;
    private static final int LEGACY_SCOREBOARD_ENTRY_LIMIT = 40;
    private static final Set<String> shutdownListenerRegistrations = new HashSet<>();
    private static boolean adapterInitializationAttempted = false;

    private ScoreboardUtil() {
    }

    public static Scoreboard createSidebarScoreboard(Plugin plugin, String displayName, List<String> scoreboardContents) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = registerSidebarObjective(plugin, scoreboard, DEFAULT_SIDEBAR_OBJECTIVE, displayName);
        setSidebarLines(objective, scoreboardContents);
        return scoreboard;
    }

    public static Scoreboard lazyScoreboard(Plugin plugin, Player player, String displayName, List<String> scoreboardContents) {
        Scoreboard scoreboard = createSidebarScoreboard(plugin, displayName, scoreboardContents);
        player.setScoreboard(scoreboard);
        return scoreboard;
    }

    public static Scoreboard temporaryScoreboard(Plugin plugin, Player player, String displayName, List<String> scoreboardContents, int ticksTimeout) {
        Scoreboard scoreboard = lazyScoreboard(plugin, player, displayName, scoreboardContents);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.getScoreboard().equals(scoreboard))
                    clearScoreboard(player);
            }
        }.runTaskLater(plugin, ticksTimeout);

        return scoreboard;
    }

    public static void clearScoreboard(Player player) {
        player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
    }

    public static Objective registerSidebarObjective(Plugin plugin, Scoreboard scoreboard, String objectiveName, String displayName) {
        Objective objective = registerSidebarObjective(scoreboard, objectiveName, displayName);
        hideScoreboardNumbers(plugin, objective);
        return objective;
    }

    public static Objective registerSidebarObjective(Scoreboard scoreboard, String objectiveName, String displayName) {
        Objective objective = scoreboard.registerNewObjective(objectiveName, "dummy", displayName == null ? "" : displayName);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        hideScoreboardNumbers(objective);
        return objective;
    }

    public static boolean hideScoreboardNumbers(Plugin plugin, Objective objective) {
        if (objective == null) return false;
        ensureAdapter(plugin);
        return hideScoreboardNumbers(objective);
    }

    public static boolean hideScoreboardNumbers(Objective objective) {
        if (objective == null || !NMSManager.isEnabled() || NMSManager.getAdapter() == null) return false;
        return NMSManager.getAdapter().hideScoreboardNumbers(objective);
    }

    private static void setSidebarLines(Objective objective, List<String> scoreboardContents) {
        if (scoreboardContents == null) return;
        int lineCount = Math.min(scoreboardContents.size(), MAX_SIDEBAR_LINES);
        for (int i = 0; i < lineCount; i++) {
            Score score = objective.getScore(trimScoreboardEntry(scoreboardContents.get(i)));
            score.setScore(i);
        }
    }

    private static String trimScoreboardEntry(String entry) {
        if (entry == null) return "";
        if (entry.length() <= LEGACY_SCOREBOARD_ENTRY_LIMIT) return entry;
        return entry.substring(0, LEGACY_SCOREBOARD_ENTRY_LIMIT - 1);
    }

    private static synchronized void ensureAdapter(Plugin plugin) {
        if (plugin == null || !plugin.isEnabled()) return;
        registerShutdownListener(plugin);
        if (NMSManager.isEnabled() && NMSManager.getAdapter() != null) return;
        if (adapterInitializationAttempted) return;
        adapterInitializationAttempted = true;
        NMSManager.initializeAdapter(plugin);
    }

    private static synchronized void registerShutdownListener(Plugin plugin) {
        if (plugin == null || shutdownListenerRegistrations.contains(plugin.getName())) return;
        shutdownListenerRegistrations.add(plugin.getName());
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPluginDisable(PluginDisableEvent event) {
                if (!event.getPlugin().getName().equals(plugin.getName())) return;
                NMSManager.shutdown();
                synchronized (ScoreboardUtil.class) {
                    shutdownListenerRegistrations.remove(plugin.getName());
                    adapterInitializationAttempted = false;
                }
            }
        }, plugin);
    }
}
