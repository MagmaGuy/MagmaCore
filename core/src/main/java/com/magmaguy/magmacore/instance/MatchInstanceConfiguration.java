package com.magmaguy.magmacore.instance;

import lombok.Builder;
import lombok.Getter;
import org.bukkit.GameMode;
import org.bukkit.Location;

@Getter
@Builder
public class MatchInstanceConfiguration {
    // Default values are set directly in the field declarations
    @Builder.Default
    private Location lobbyLocation = null;
    @Builder.Default
    private Location startLocation = null;
    @Builder.Default
    private Location exitLocation = null;
    @Builder.Default
    private Location fallbackLocation = null;

    @Builder.Default
    private boolean worldBased = false;

    @Builder.Default
    private boolean spectatable = false;

    @Builder.Default
    private boolean respawnable = false;

    @Builder.Default
    private boolean scaleEliteMobsHealthWithPlayerCount = false;

    @Builder.Default
    private int minPlayers = 0;

    @Builder.Default
    private int maxPlayers = 1;

    @Builder.Default
    private int lives = 1;

    private String dungeonPermission;

    @Builder.Default
    private String failedToJoinOngoingMatchAsPlayerMessage = "Can't join this match - it has already started!";

    @Builder.Default
    private String failedToJoinOngoingMatchAsPlayerInstanceIsFull = "Can't join this match - the instance is already full!";

    @Builder.Default
    private String failedToJoinOngoingMatchAsPlayerNoPermission = "Can't join this match - you don't have the permission!";

    @Builder.Default
    private String failedToJoinMatchAsSpectatorNoSpectatorsAllowedMessage = "Can't join this match - spectators are not allowed!";

    @Builder.Default
    private String failedToJoinMatchAsSpectatorNoPermission = "Can't spectate this match - you don't have the permission!";

    @Builder.Default
    private String matchJoinAsPlayerMessage = "[Default message] Welcome to the match, $player!";

    private String matchJoinAsPlayerTitle;
    private String matchJoinAsPlayerSubtitle;
    private String matchJoinAsSpectatorTitle;
    private String matchJoinAsSpectatorSubtitle;

    @Builder.Default
    private String matchJoinAsSpectatorMessage = "[Default message] Welcome to the match, $player!";

    @Builder.Default
    private String matchLeaveAsPlayerMessage = "[Default message] You have left the match, $player!";

    @Builder.Default
    private String matchLeaveAsSpectatorMessage = "[Default message] You have left the match, spectator!";

    @Builder.Default
    private String matchFailedToStartNotEnoughPlayersMessage = "This match requires $count players before starting - can't start yet!";

    @Builder.Default
    private String matchWaitingMessageMessage = "[Default message] Run command /<insert label here> start to start the match! (You shouldn't be seeing this debug message!)";

    @Builder.Default
    private String matchStartingMessage = "Match starting!";

    @Builder.Default
    private String matchStartingTitle = "Match starting!";

    @Builder.Default
    private String matchStartingSubtitle = "in $count...";

    @Builder.Default
    private String preventTeleportInMessage = "You have attempted to teleport into an ongoing match - you can't do that!";

    @Builder.Default
    private String preventTeleportOutMessage = "You have attempted to teleport from an ongoing match - you can't do that!";

    @Builder.Default
    private GameMode matchLeaveDefaultGamemode = GameMode.SURVIVAL;

    @Builder.Default
    private GameMode matchGamemode = null;

    @Builder.Default
    private boolean isProtected = true;
    @Builder.Default
    private boolean isPvpPrevented = false;
    @Builder.Default
    private boolean isRedstonePrevented = true;
}