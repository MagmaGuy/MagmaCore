package com.magmaguy.magmacore.match;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;

/** Player lifecycle events the core reacts to. */
final class MatchListener implements Listener {
    // Recovery runs first so nothing else sees a player still wearing their match state.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (MatchCore.participant(player) != null) return;
        for (PlayerCustody custody : MatchCore.custodies()) {
            if (!player.isOnline()) return;
            custody.recover(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Match match = MatchCore.matchOf(event.getPlayer());
        if (match != null) match.leave(event.getPlayer(), LeaveReason.DISCONNECT);
    }

    /**
     * Lethal damage never kills a participant: it is cancelled and handed to the death policy.
     * Runs at HIGHEST, the last priority Bukkit allows to change an event, so it sees the damage
     * other plugins settled on. Ported from EliteMobs' MatchInstanceEvents.onPlayerDamage.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Match match = MatchCore.matchOf(player);
        if (match == null) return;
        MatchPlayer participant = match.getMatchPlayer(player);
        if (participant == null) return;

        // Border damage inside a match almost always means the world was copied without its
        // border data, which reads as "the match closes the moment players walk in".
        if (event.getCause() == EntityDamageEvent.DamageCause.WORLD_BORDER) {
            Location location = player.getLocation();
            Logger.warn("Player " + player.getName() + " took WORLD_BORDER damage inside match world '"
                    + (location.getWorld() != null ? location.getWorld().getName() : "?") + "' at "
                    + location.getX() + "," + location.getY() + "," + location.getZ()
                    + ". The world's border probably does not cover the play area. On Paper 1.21.6+ border "
                    + "data lives in its own file inside the dimension data folder, and a copied world may not "
                    + "include it.");
        }

        // Backup for the per-tick rescue, which a lag spike can let slip below the void line.
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID && participant.role == MatchRole.PLAYER
                && match.getSettings().getStart() != null) {
            event.setCancelled(true);
            MatchWatchdog.rescue(match, participant);
            return;
        }

        if (event.getFinalDamage() < player.getHealth()) return;
        event.setCancelled(true);
        if (match.phase != MatchPhase.ONGOING) {
            match.leave(player, LeaveReason.DIED);
            return;
        }
        if (participant.role != MatchRole.PLAYER) return;
        match.handleLethalDamage(participant);
    }

    @EventHandler
    public void onBannerBreak(BlockBreakEvent event) {
        reviveAt(event.getBlock());
    }

    @EventHandler
    public void onBannerHit(BlockDamageEvent event) {
        reviveAt(event.getBlock());
    }

    private static void reviveAt(Block block) {
        for (Match match : List.copyOf(MatchCore.matches())) {
            if (match.phase != MatchPhase.ONGOING) continue;
            ReviveBanner banner = match.reviveBanners.get(block);
            if (banner != null) banner.clear(true);
        }
    }
}
