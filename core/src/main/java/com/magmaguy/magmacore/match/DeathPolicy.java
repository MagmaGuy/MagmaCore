package com.magmaguy.magmacore.match;

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

    int initialLives();

    /** Called after the core cancelled the lethal damage, cleared effects and ran onDeath. */
    void handle(Match match, MatchPlayer player);
}
