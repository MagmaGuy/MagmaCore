package com.magmaguy.magmacore.match;

import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;

/**
 * The cross-plugin "this player is taken" marker. Player metadata is readable by every
 * relocated copy of MagmaCore because it is keyed by a plain string, which is what enforces
 * one match per player across plugins.
 */
final class MatchMarker {
    private MatchMarker() {
    }

    static void set(Player player, Match match) {
        set(player, MatchCore.plugin().getName() + ":" + match.getRuntimeId());
    }

    static void setPending(Player player) {
        set(player, MatchCore.plugin().getName() + ":pending");
    }

    private static void set(Player player, String value) {
        player.setMetadata(MatchCore.MARKER_KEY, new FixedMetadataValue(MatchCore.plugin(), value));
    }

    /** Removes only this plugin's marker. */
    static void clear(Player player) {
        player.removeMetadata(MatchCore.MARKER_KEY, MatchCore.plugin());
    }

    static boolean occupied(Player player) {
        return player.hasMetadata(MatchCore.MARKER_KEY);
    }
}
