package com.magmaguy.magmacore.match;

import org.bukkit.block.Block;

/**
 * Floating text or effects over a revive banner. The core draws nothing itself because MagmaCore
 * has no text-display dependency; EliteMobs supplies its FakeText marker.
 */
public interface ReviveMarker {
    ReviveMarker NONE = new ReviveMarker() {
        @Override
        public void show(Block banner, MatchPlayer dead, int livesLeft) {
        }

        @Override
        public void hide() {
        }
    };

    void show(Block banner, MatchPlayer dead, int livesLeft);

    void hide();
}
