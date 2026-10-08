package com.magmaguy.magmacore.rails;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/**
 * A packet-only vehicle carrying one real player. Only the clients of its viewers know the vehicle;
 * the server never spawns it, so it cannot be broken, pushed, saved or picked up.
 */
public interface PacketSeat {
    /** Moves the vehicle for its viewers and keeps the rider's server-side position with it. */
    void move(Location location);

    /** Removes the vehicle for every viewer, which unseats the rider on each client. Idempotent. */
    void close();

    /** Installed by the active NMS adapter. */
    interface Provider {
        PacketSeat open(Player rider, EntityType vehicle, Location location);
    }
}
