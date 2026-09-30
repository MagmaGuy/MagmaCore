package com.magmaguy.magmacore.match;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Keeps participants in and strangers out. Ported from EliteMobs' MatchInstanceEvents
 * teleport handlers, plus the door rule for non-participants.
 */
final class TeleportGuard implements Listener {
    // Remember only events accepted by the staff exception, for final validation.
    private final Map<PlayerTeleportEvent, Match> staffTeleports = new IdentityHashMap<>();

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (MatchMovement.authorizes(event)) return;
        Player player = event.getPlayer();
        Match current = MatchCore.matchOf(player);
        if (MatchMovement.permitsStaffMove(event, current)) {
            staffTeleports.put(event, current);
            return;
        }

        if (current != null) {
            if (touches(current, event.getFrom()) || touches(current, event.getTo())) {
                event.setCancelled(true);
                return;
            }
            MatchPlayer participant = current.getMatchPlayer(player);
            if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE
                    && participant != null && participant.role == MatchRole.SPECTATOR) {
                event.setCancelled(true);
                return;
            }
            // Leaving a match that has not started is allowed, and counts as quitting it.
            if (current.phase == MatchPhase.WAITING) {
                current.leave(player, LeaveReason.QUIT);
                return;
            }
            event.setCancelled(true);
            return;
        }

        refuseStrangers(event, player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void validateAuthorizedMovement(PlayerTeleportEvent event) {
        Match permitted = staffTeleports.remove(event);
        if (event.isCancelled()) return;
        if (permitted != null && !MatchMovement.permitsStaffMove(event, permitted)) {
            event.setCancelled(true);
            return;
        }
        if (MatchMovement.hasAuthorization(event.getPlayer()) && !MatchMovement.authorizes(event))
            event.setCancelled(true);
    }

    private static void refuseStrangers(PlayerTeleportEvent event, Player player) {
        Location to = event.getTo();
        if (to == null) return;
        for (Match match : MatchCore.matches()) {
            if (!match.isOpen()) continue;
            MatchSettings settings = match.getSettings();
            if (!settings.getSpace().guardsEntryDuring(match.phase)) continue;
            if (!match.contains(to) || match.contains(event.getFrom())) continue;
            String bypass = settings.getBypassPermission();
            if (bypass != null && player.hasPermission(bypass)) continue;
            event.setCancelled(true);
            return;
        }
    }

    private static boolean touches(Match match, Location location) {
        if (location == null) return false;
        World world = location.getWorld();
        return world != null && match.getSettings().getSpace().worlds().contains(world);
    }
}
