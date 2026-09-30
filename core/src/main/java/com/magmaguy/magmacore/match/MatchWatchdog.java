package com.magmaguy.magmacore.match;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/** One task per open match, running every tick. Ported from EliteMobs' MatchInstance watchdogs. */
final class MatchWatchdog implements Runnable {
    private static final long WAITING_HINT_PERIOD_TICKS = 20L * 60L;
    private final Match match;
    private BukkitTask task;
    private long ticks;

    private MatchWatchdog(Match match) {
        this.match = match;
    }

    static MatchWatchdog start(Match match) {
        MatchWatchdog watchdog = new MatchWatchdog(match);
        watchdog.task = Bukkit.getScheduler().runTaskTimer(MatchCore.plugin(), watchdog, 0L, 1L);
        return watchdog;
    }

    void cancel() {
        if (task != null) task.cancel();
        task = null;
    }

    @Override
    public void run() {
        if (match.isDestroyed()) {
            cancel();
            return;
        }
        if (match.phase == MatchPhase.WAITING && ticks % WAITING_HINT_PERIOD_TICKS == 0) remindWaitingPlayers();
        ticks++;
    }

    private void remindWaitingPlayers() {
        MatchSettings settings = match.getSettings();
        for (MatchPlayer participant : match.getMatchPlayers())
            Feedback.message(participant.getPlayer(), settings.getMessages().getWaitingHint(),
                    "$count", settings.getMinPlayers());
    }
}
