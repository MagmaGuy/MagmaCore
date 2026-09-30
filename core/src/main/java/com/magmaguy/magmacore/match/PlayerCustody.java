package com.magmaguy.magmacore.match;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

import java.util.Objects;

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

    /**
     * Crash-safe custody under {@code key}: the original inventory, held slot, flight and return
     * location are flushed to playerdata before the match changes anything, restored on leave,
     * and restored on the next join after a crash. A restore that fails kicks the player and
     * keeps the lease. Register it with {@link MatchCore#registerCustody} at enable.
     */
    static PlayerCustody snapshot(NamespacedKey key) {
        return new SnapshotCustody(Objects.requireNonNull(key, "key"));
    }

    /** Takes custody before the match changes anything. Throws when it cannot. */
    void capture(Player player);

    /** Restores the captured state without moving the player. */
    void restore(Player player);

    /** Join-time recovery after a crash: restores and moves the player back. */
    void recover(Player player);
}
