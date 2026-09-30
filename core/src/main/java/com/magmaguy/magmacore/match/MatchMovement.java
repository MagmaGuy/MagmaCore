package com.magmaguy.magmacore.match;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Narrowly scoped player movement that the teleport guard accepts. An authorization exists
 * only on the synchronous call stack, is bound to one player, one exact destination and one
 * cause, and is always removed in {@code finally}; it cannot authorize another player's
 * teleport or a later event. Ported from EliteMobs' InstancePlayerMovement.
 */
final class MatchMovement {
    private static final double DESTINATION_EPSILON_SQUARED = 1.0E-10D;
    private static final ThreadLocal<Deque<Authorization>> AUTHORIZATIONS = new ThreadLocal<>();

    private MatchMovement() {
    }

    /** The match admits, starts, rescues, revives or evacuates one player. */
    static boolean moveForMatch(Match match, Player player, Location destination, MoveReason reason) {
        Match current = MatchCore.matchOf(player);
        return moveForMatch(match, player, current == match ? match.getMatchPlayer(player) : null, destination, reason);
    }

    /**
     * As above, teleporting through {@code participant} when given. Leaving passes the
     * participant after it is untracked, so the exit still runs through its teleport override.
     */
    static boolean moveForMatch(Match match, Player player, MatchPlayer participant, Location destination,
                                MoveReason reason) {
        if (!Bukkit.isPrimaryThread() || match == null || destination == null || destination.getWorld() == null
                || !player.isOnline() || !player.isValid()) return false;
        Match current = MatchCore.matchOf(player);
        if (!permitsLifecycleMovement(player, destination, match, current)) return false;
        return teleportAuthorized(player, destination, PlayerTeleportEvent.TeleportCause.PLUGIN,
                current, false, match, () -> participant != null
                        ? participant.teleport(destination, reason)
                        : player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN));
    }

    private static boolean permitsLifecycleMovement(Player player, Location destination, Match match, Match current) {
        if (current != null && current != match) return false;
        if (match.contains(destination))
            return match.isOpen() && current == match && match.getMatchPlayer(player) != null;
        return match.contains(player.getLocation());
    }

    static boolean teleportAuthorized(Player player, Location destination, PlayerTeleportEvent.TeleportCause cause,
                                      Match match, boolean leaving, Match lifecycleOwner, BooleanSupplier teleport) {
        Authorization authorization = new Authorization(
                player.getUniqueId(),
                player.getWorld().getUID(),
                destination.getWorld().getUID(),
                destination.clone(),
                cause,
                match,
                leaving,
                lifecycleOwner);
        Deque<Authorization> stack = AUTHORIZATIONS.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            AUTHORIZATIONS.set(stack);
        }
        stack.push(authorization);
        try {
            return teleport.getAsBoolean();
        } finally {
            Authorization removed = stack.pop();
            if (removed != authorization)
                throw new IllegalStateException("Match movement authorization stack was corrupted");
            if (stack.isEmpty()) AUTHORIZATIONS.remove();
        }
    }

    static boolean authorizes(PlayerTeleportEvent event) {
        Location destination = event.getTo();
        if (destination == null || destination.getWorld() == null) return false;
        Deque<Authorization> stack = AUTHORIZATIONS.get();
        if (stack == null) return false;
        Player player = event.getPlayer();
        for (Authorization authorization : stack) {
            if (!authorization.playerId().equals(player.getUniqueId())) continue;
            if (!authorization.sourceWorldId().equals(event.getFrom().getWorld().getUID())) continue;
            if (!authorization.destinationWorldId().equals(destination.getWorld().getUID())) continue;
            if (authorization.cause() != event.getCause()) continue;
            if (authorization.destination().distanceSquared(destination) > DESTINATION_EPSILON_SQUARED) continue;

            Match current = MatchCore.matchOf(player);
            if (current != authorization.match()) return false;
            if (authorization.lifecycleOwner() != null)
                return permitsLifecycleMovement(player, destination, authorization.lifecycleOwner(), current);
            if (current == null) return true;
            if (authorization.leaving())
                return !current.contains(destination) && current.getMatchPlayer(player) != null;
            MatchPlayer participant = current.getMatchPlayer(player);
            return (current.phase == MatchPhase.ONGOING || current.waitingToStart(player))
                    && participant != null && participant.role == MatchRole.PLAYER
                    && spansWorld(current, authorization.sourceWorldId())
                    && current.contains(event.getFrom())
                    && current.contains(destination);
        }
        return false;
    }

    static boolean hasAuthorization(Player player) {
        Deque<Authorization> stack = AUTHORIZATIONS.get();
        if (stack == null) return false;
        UUID playerId = player.getUniqueId();
        return stack.stream().anyMatch(authorization -> authorization.playerId().equals(playerId));
    }

    private static boolean spansWorld(Match match, UUID worldId) {
        for (World world : match.getSettings().getSpace().worlds())
            if (world.getUID().equals(worldId)) return true;
        return false;
    }

    private record Authorization(
            UUID playerId,
            UUID sourceWorldId,
            UUID destinationWorldId,
            Location destination,
            PlayerTeleportEvent.TeleportCause cause,
            Match match,
            boolean leaving,
            Match lifecycleOwner) {
    }
}
