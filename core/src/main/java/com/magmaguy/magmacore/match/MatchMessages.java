package com.magmaguy.magmacore.match;

import lombok.Builder;
import lombok.Getter;

/**
 * Player-facing strings. Null means the core stays silent and leaves messaging to the plugin.
 * The core replaces $count and $amount, and $player in join and spectator messages; colour
 * codes use '&amp;'.
 */
@Getter
@Builder(toBuilder = true)
public final class MatchMessages {
    @Builder.Default
    private final String notAccepting = "&cThis match is not accepting players right now.";
    @Builder.Default
    private final String full = "&cThis match is full.";
    @Builder.Default
    private final String noPermission = "&cYou don't have permission to join this match.";
    @Builder.Default
    private final String alreadyInMatch = "&cYou are already in a match.";
    @Builder.Default
    private final String joinedMessage = "&aYou joined the match. It needs $count players to start.";
    @Builder.Default
    private final String joinedTitle = "Joined!";
    @Builder.Default
    private final String joinedSubtitle = null;
    @Builder.Default
    private final String spectatorMessage = "&aYou are now spectating this match.";
    @Builder.Default
    private final String spectatorTitle = null;
    @Builder.Default
    private final String spectatorSubtitle = null;
    @Builder.Default
    private final String notEnoughPlayers = "&cThis match needs $amount players to start.";
    @Builder.Default
    private final String startingTitle = "Match starting!";
    @Builder.Default
    private final String startingSubtitle = "in $count...";
    @Builder.Default
    private final String waitingHint = "&7Waiting to start. This match needs $count players.";

    public static MatchMessages defaults() {
        return builder().build();
    }
}
