package com.magmaguy.magmacore.match;

import org.bukkit.entity.Player;

/**
 * The plugin's public API adapter. The core fires no Bukkit events of its own; it calls this,
 * and each plugin fires events from its own API package so add-ons never import relocated
 * MagmaCore classes. Cancellable callbacks return false to veto, and a callback that throws
 * counts as a veto.
 */
public interface MatchApi {
    MatchApi NONE = new MatchApi() {
    };

    default boolean instantiateAttempt(Match match) {
        return true;
    }

    default boolean joinAttempt(Match match, Player player) {
        return true;
    }

    default void joined(Match match, MatchPlayer player) {
    }

    default boolean startAttempt(Match match) {
        return true;
    }

    default void started(Match match) {
    }

    default void left(Match match, MatchPlayer player, LeaveReason reason) {
    }

    default void ended(Match match, MatchOutcome outcome) {
    }

    default void destroyed(Match match) {
    }
}
