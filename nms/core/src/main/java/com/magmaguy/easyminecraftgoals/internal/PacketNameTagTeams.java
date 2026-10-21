package com.magmaguy.easyminecraftgoals.internal;

import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.easyminecraftgoals.NMSManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-only scoreboard teams that keep a living entity's name tag visible to a viewer while something rides that
 * entity. Minecraft hides the name of a team-less vehicle; an entity on a team follows the team's name-tag rule
 * instead, so each kept subject gets a one-member team with nameTagVisibility ALWAYS on the viewer's client only.
 * Mobs still show their name only when it is set visible or looked at, as without a rider. Callers release a subject
 * while it has a real team on the viewer's Bukkit scoreboard.
 * <p>
 * Other plugins also send teams straight to clients, invisible to the Bukkit scoreboard. Moving their member into our
 * team makes their later "remove this member" packet fail on the client, which disconnects the viewer. So each
 * viewer's packet handler mirrors every team packet the client receives: a subject already on another team is not
 * kept, and an entry our team held is put back into the sender's team just before such a removal reaches the client.
 */
public final class PacketNameTagTeams {
    private static final String PREFIX = "emg_";
    private static final Map<UUID, Set<UUID>> KEPT = new ConcurrentHashMap<>();
    /** Per viewer: the team the client files each entry under, as the packets it received say. */
    private static final Map<UUID, Map<String, String>> CLIENT_TEAMS = new ConcurrentHashMap<>();
    /** Per viewer: entries one of our teams has held on that client, whose other teams we may have overridden. */
    private static final Map<UUID, Set<String>> HELD = new ConcurrentHashMap<>();

    private PacketNameTagTeams() { }

    /** Sixteen characters at most, stable per subject. */
    public static String teamName(UUID subject) {
        String hex = Long.toHexString(subject.getMostSignificantBits() ^ subject.getLeastSignificantBits());
        return PREFIX + hex.substring(0, Math.min(12, hex.length()));
    }

    /** The scoreboard entry Minecraft files the entity under: a player's name, any other entity's UUID string. */
    public static String scoreboardEntry(Entity subject) {
        return subject instanceof Player player ? player.getName() : subject.getUniqueId().toString();
    }

    /** Sends the team once per viewer and subject; later calls are free. A subject on another team is left there. */
    public static void keep(Player viewer, Entity subject) {
        NMSAdapter adapter = NMSManager.getAdapter();
        if (adapter == null || viewer == null || subject == null || viewer.equals(subject)) return;
        Set<UUID> subjects = KEPT.computeIfAbsent(viewer.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet());
        if (subjects.contains(subject.getUniqueId())) return;
        String team = teamName(subject.getUniqueId());
        String entry = scoreboardEntry(subject);
        String current = clientTeams(viewer.getUniqueId()).get(entry);
        if (current != null && !current.equals(team)) return;
        subjects.add(subject.getUniqueId());
        // Recorded here too: a team sent before the viewer's packet handler is injected is never observed.
        clientTeams(viewer.getUniqueId()).put(entry, team);
        HELD.computeIfAbsent(viewer.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(entry);
        adapter.sendNameTagTeam(viewer, team, entry, true);
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

    /**
     * Mirrors a team packet on its way to the viewer's client. Returns the entries to add back to {@code team} before
     * the packet goes out: entries it removes from another plugin's team that the client may still file under ours.
     */
    public static List<String> observe(UUID viewer, String team, boolean teamRemoved, boolean playersAdded,
                                       boolean playersRemoved, Collection<String> players) {
        Map<String, String> teams = clientTeams(viewer);
        if (teamRemoved) {
            teams.values().removeIf(team::equals);
            return List.of();
        }
        if (playersAdded) for (String entry : players) teams.put(entry, team);
        if (!playersRemoved) return List.of();
        Set<String> held = HELD.getOrDefault(viewer, Set.of());
        List<String> restored = new ArrayList<>();
        for (String entry : players) {
            if (!team.startsWith(PREFIX) && held.contains(entry) && !team.equals(teams.get(entry))) restored.add(entry);
            teams.remove(entry);
        }
        return restored;
    }

    private static Map<String, String> clientTeams(UUID viewer) {
        return CLIENT_TEAMS.computeIfAbsent(viewer, ignored -> new ConcurrentHashMap<>());
    }

    /** A viewer who left needs no packets; forget what they were shown. */
    public static void clearViewer(UUID viewer) {
        KEPT.remove(viewer);
        CLIENT_TEAMS.remove(viewer);
        HELD.remove(viewer);
    }

    public static void clear() {
        KEPT.clear();
        CLIENT_TEAMS.clear();
        HELD.clear();
    }
}
