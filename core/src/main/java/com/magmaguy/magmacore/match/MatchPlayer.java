package com.magmaguy.magmacore.match;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;

/** One participant of one match. Plugins subclass it through {@link Match#createPlayer}. */
public class MatchPlayer {
    private final Player player;
    private final Match match;
    private final Location previousLocation;
    private final GameMode previousGameMode;
    MatchRole role = MatchRole.PLAYER;
    int lives;
    boolean entered;
    LeaveReason leaveReason;
    Location lastSafeLocation;
    int consecutiveRescues;

    protected MatchPlayer(Player player, Match match) {
        this.player = player;
        this.match = match;
        this.previousLocation = player.getLocation().clone();
        this.previousGameMode = player.getGameMode();
    }

    /**
     * Moves the player. The core calls this inside its teleport authorization, so overrides may
     * add behaviour around the move but cannot widen or keep the authorization.
     */
    protected boolean teleport(Location destination, MoveReason reason) {
        return player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    public Player getPlayer() {
        return player;
    }

    public UUID getUniqueId() {
        return player.getUniqueId();
    }

    public Match getMatch() {
        return match;
    }

    public Location getPreviousLocation() {
        return previousLocation.clone();
    }

    public GameMode getPreviousGameMode() {
        return previousGameMode;
    }

    public MatchRole getRole() {
        return role;
    }

    public int getLives() {
        return lives;
    }

    public LeaveReason getLeaveReason() {
        return leaveReason;
    }

    /** Whether the entry teleport has put this player into the match yet. */
    public boolean hasEntered() {
        return entered;
    }
}
