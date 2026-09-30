package com.magmaguy.magmacore.match;

import org.bukkit.entity.Player;

/** Whether the core takes over a player's inventory and state for the match. */
public interface PlayerCustody {
    /** The match leaves inventories alone. */
    static PlayerCustody none() {
        return new PlayerCustody() {
            @Override
            public void capture(Player player) {
            }

            @Override
            public void restore(Player player) {
            }

            @Override
            public void recover(Player player) {
            }
        };
    }

    /** Takes custody before the match changes anything. Throws when it cannot. */
    void capture(Player player);

    /** Restores the captured state without moving the player. */
    void restore(Player player);

    /** Join-time recovery after a crash: restores and moves the player back. */
    void recover(Player player);
}
