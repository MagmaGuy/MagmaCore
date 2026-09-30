package com.magmaguy.magmacore.match;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The time between a player asking for a match and its space being ready, such as a world copy
 * or generation. The roster is marked as taken, so nobody can double-request or join elsewhere.
 * The request is cancelled if a member quits, on timeout, or by the plugin, and each cancel runs
 * the plugin's cleanup so only what this request created is deleted.
 */
public final class PendingMatch {
    private final List<Player> roster;
    private final List<Consumer<CancelReason>> cleanups = new ArrayList<>();
    private BukkitTask timeout;
    private boolean active = true;
    private CancelReason cancelledWith;

    private PendingMatch(List<Player> roster) {
        this.roster = roster;
    }

    /** Returns null when the roster is empty, or any member is offline or already taken by any plugin. */
    public static PendingMatch request(Collection<? extends Player> roster, long timeoutTicks) {
        MatchCore.plugin();
        LinkedHashMap<UUID, Player> unique = new LinkedHashMap<>();
        for (Player player : roster)
            if (player != null) unique.putIfAbsent(player.getUniqueId(), player);
        if (unique.isEmpty()) return null;
        for (Player player : unique.values()) {
            if (!player.isOnline() || !player.isValid()) return null;
            if (MatchMarker.occupied(player) || MatchCore.participant(player) != null) return null;
        }
        PendingMatch pending = new PendingMatch(List.copyOf(unique.values()));
        for (Player player : pending.roster) {
            MatchMarker.setPending(player);
            MatchCore.trackPending(player, pending);
        }
        pending.timeout = Bukkit.getScheduler().runTaskLater(MatchCore.plugin(),
                () -> pending.cancel(CancelReason.TIMEOUT), timeoutTicks);
        return pending;
    }

    public boolean isActive() {
        return active;
    }

    public List<Player> getRoster() {
        return roster;
    }

    /** Cleanup for what the plugin built for this request. Runs at once if already cancelled. */
    public void onCancel(Consumer<CancelReason> cleanup) {
        if (cancelledWith != null) run(cleanup, cancelledWith);
        else if (active) cleanups.add(cleanup);
    }

    public void cancel() {
        cancel(CancelReason.CANCELLED);
    }

    void cancel(CancelReason reason) {
        if (!active) return;
        active = false;
        cancelledWith = reason;
        release();
        for (Consumer<CancelReason> cleanup : List.copyOf(cleanups)) run(cleanup, reason);
        cleanups.clear();
    }

    /**
     * Opens the match and admits the roster. If the request was cancelled, the match cannot
     * open, or admission fails, the match is destroyed and the reason is returned.
     */
    public AdmissionResult complete(Match match) {
        if (!active) {
            match.destroy();
            return AdmissionResult.NOT_ACCEPTING;
        }
        active = false;
        cleanups.clear();
        release();
        if (!match.open()) {
            match.destroy();
            return AdmissionResult.NOT_ACCEPTING;
        }
        AdmissionResult result = match.admit(roster);
        if (result != AdmissionResult.ADMITTED) match.destroy();
        return result;
    }

    private void release() {
        if (timeout != null) timeout.cancel();
        for (Player player : roster) {
            MatchMarker.clear(player);
            MatchCore.untrackPending(player, this);
        }
    }

    private static void run(Consumer<CancelReason> cleanup, CancelReason reason) {
        try {
            cleanup.accept(reason);
        } catch (RuntimeException failure) {
            Logger.warn("A pending match cleanup failed: " + failure);
        }
    }

    public enum CancelReason {
        QUIT, TIMEOUT, CANCELLED
    }
}
