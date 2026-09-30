package com.magmaguy.magmacore.match;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Player lifecycle events the core reacts to. */
final class MatchListener implements Listener {
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Match match = MatchCore.matchOf(event.getPlayer());
        if (match != null) match.leave(event.getPlayer(), LeaveReason.DISCONNECT);
    }
}
