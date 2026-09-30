package com.magmaguy.magmacore.match;

import java.util.Objects;
import java.util.function.Function;

/** What happens when an active participant takes lethal damage during an ongoing match. */
public interface DeathPolicy {
    /** The dead player leaves with {@link LeaveReason#DIED}. */
    static DeathPolicy eliminate() {
        return new DeathPolicy() {
            @Override
            public int initialLives() {
                return 1;
            }

            @Override
            public void handle(Match match, MatchPlayer player) {
                match.leave(player.getPlayer(), LeaveReason.DIED);
            }
        };
    }

    /**
     * The dead player spectates beside a banner that any player can punch to revive them, while
     * they have lives left. When no active player remains the match ends in defeat.
     */
    static DeathPolicy spectateAndRevive(int lives) {
        return spectateAndRevive(lives, participant -> ReviveMarker.NONE);
    }

    static DeathPolicy spectateAndRevive(int lives, Function<MatchPlayer, ReviveMarker> markers) {
        if (lives < 1) throw new IllegalArgumentException("A revive policy needs at least one life");
        Objects.requireNonNull(markers, "markers");
        return new DeathPolicy() {
            @Override
            public int initialLives() {
                return lives;
            }

            @Override
            public void handle(Match match, MatchPlayer player) {
                match.spectateUntilRevived(player, markers);
            }
        };
    }

    int initialLives();

    /** Called after the core cancelled the lethal damage, cleared effects and ran onDeath. */
    void handle(Match match, MatchPlayer player);
}
