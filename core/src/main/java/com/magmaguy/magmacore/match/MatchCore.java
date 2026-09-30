package com.magmaguy.magmacore.match;

import com.magmaguy.magmacore.util.WorldFolderResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Entry point of the match core. Every plugin ships its own relocated copy of MagmaCore, so this
 * registry only ever sees the owning plugin's matches. The player metadata marker under
 * {@link #MARKER_KEY} is what lets every copy see that a player is already taken.
 */
public final class MatchCore {
    public static final String MARKER_KEY = "magmacore_match";
    private static final Set<Match> matches = new LinkedHashSet<>();
    private static final Map<UUID, MatchPlayer> participants = new HashMap<>();
    private static final List<Listener> listeners = new ArrayList<>();
    private static JavaPlugin plugin;

    private MatchCore() {
    }

    public static void enable(JavaPlugin owner) {
        if (plugin != null) return;
        plugin = Objects.requireNonNull(owner, "owner");
        listen(new MatchListener());
        listen(new TeleportGuard());
    }

    /**
     * Moves a player inside one world. For a participant, both ends must lie inside their match
     * and they must be playing or waiting to start; other players move normally. For plugin
     * mechanics, add-ons and test harnesses that reposition players.
     */
    public static boolean moveWithin(Player player, Location destination, PlayerTeleportEvent.TeleportCause cause) {
        return MatchMovement.moveWithin(player, destination, cause);
    }

    /**
     * Moves a player out of their match. It counts as leaving with {@link LeaveReason#QUIT} only
     * if the teleport completes; a destination inside the match is refused.
     */
    public static boolean moveOut(Player player, Location destination, PlayerTeleportEvent.TeleportCause cause) {
        return MatchMovement.moveOut(player, destination, cause);
    }

    /** Destroys every match of this plugin with {@link LeaveReason#SHUTDOWN}. */
    public static void shutdown() {
        if (plugin == null) return;
        try {
            for (Match match : new ArrayList<>(matches)) match.shutdownDestroy();
        } finally {
            matches.clear();
            participants.clear();
            listeners.forEach(HandlerList::unregisterAll);
            listeners.clear();
            plugin = null;
        }
    }

    /**
     * Deletes unloaded temporary match worlds this plugin left behind, for example after a crash.
     * Call once during enable, before creating matches.
     */
    public static void sweepLeftoverWorlds() {
        LeftoverWorlds.sweep(plugin().getName(), WorldFolderResolver.listAllWorldNames(),
                name -> WorldFolderResolver.resolve(name).toPath(),
                name -> Bukkit.getWorld(name) != null,
                WorldFolderResolver::deleteAllLayouts);
    }

    public static Collection<Match> matches() {
        return Collections.unmodifiableSet(matches);
    }

    /** The match of this plugin the player takes part in, or null. */
    public static Match matchOf(Player player) {
        MatchPlayer participant = participants.get(player.getUniqueId());
        return participant == null ? null : participant.getMatch();
    }

    static JavaPlugin plugin() {
        if (plugin == null) throw new IllegalStateException("MatchCore.enable(plugin) has not been called");
        return plugin;
    }

    static void listen(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin());
        listeners.add(listener);
    }

    static void register(Match match) {
        matches.add(match);
    }

    static void unregister(Match match) {
        matches.remove(match);
    }

    static MatchPlayer participant(Player player) {
        return participants.get(player.getUniqueId());
    }

    static void track(MatchPlayer player) {
        participants.put(player.getUniqueId(), player);
    }

    static void untrack(MatchPlayer player) {
        participants.remove(player.getUniqueId(), player);
    }
}
