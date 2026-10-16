package com.magmaguy.easyminecraftgoals.internal;

import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.easyminecraftgoals.NMSManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-only scoreboard teams that keep a player's name tag visible to a viewer while something rides that
 * player. Minecraft hides the name of a team-less vehicle; a player on a team follows the team's name-tag rule
 * instead, so each kept subject gets a one-member team with nameTagVisibility ALWAYS on the viewer's client only.
 * Callers keep a subject only while the subject has no real team on the viewer's scoreboard; a real team packet
 * moves the subject out of this one on the client, and the next reconcile puts it back if the subject leaves it.
 */
public final class PacketNameTagTeams {
    private static final Map<UUID, Set<UUID>> KEPT = new ConcurrentHashMap<>();

    private PacketNameTagTeams() { }

    /** Sixteen characters at most, stable per subject. */
    public static String teamName(UUID subject) {
        String hex = Long.toHexString(subject.getMostSignificantBits() ^ subject.getLeastSignificantBits());
        return "emg_" + hex.substring(0, Math.min(12, hex.length()));
    }

    /** Sends the team once per viewer and subject; later calls are free. */
    public static void keep(Player viewer, Player subject) {
        NMSAdapter adapter = NMSManager.getAdapter();
        if (adapter == null || viewer == null || subject == null || viewer.equals(subject)) return;
        Set<UUID> subjects = KEPT.computeIfAbsent(viewer.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet());
        if (subjects.add(subject.getUniqueId()))
            adapter.sendNameTagTeam(viewer, teamName(subject.getUniqueId()), subject.getName(), true);
    }

    public static void release(Player viewer, UUID subject) {
        if (viewer == null) return;
        Set<UUID> subjects = KEPT.get(viewer.getUniqueId());
        if (subjects == null || !subjects.remove(subject)) return;
        NMSAdapter adapter = NMSManager.getAdapter();
        if (adapter != null && viewer.isOnline()) adapter.sendNameTagTeam(viewer, teamName(subject), null, false);
    }

    /** Lifts the subject's team from every viewer, for a label that goes away. */
    public static void releaseSubject(UUID subject) {
        for (Map.Entry<UUID, Set<UUID>> entry : KEPT.entrySet()) {
            if (!entry.getValue().contains(subject)) continue;
            release(Bukkit.getPlayer(entry.getKey()), subject);
        }
    }

    public static boolean isKept(UUID viewer, UUID subject) {
        Set<UUID> subjects = KEPT.get(viewer);
        return subjects != null && subjects.contains(subject);
    }

    /** A viewer who left needs no packets; forget what they were shown. */
    public static void clearViewer(UUID viewer) {
        KEPT.remove(viewer);
    }

    public static void clear() {
        KEPT.clear();
    }
}
