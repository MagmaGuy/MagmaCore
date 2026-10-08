package com.magmaguy.magmacore.rails;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.Objects;

/** Owns the packet seat provider installed by the active NMS adapter. */
public final class PacketSeats {
    private static volatile PacketSeat.Provider provider;

    private PacketSeats() { }

    public static void install(PacketSeat.Provider replacement) {
        provider = Objects.requireNonNull(replacement, "replacement");
    }

    public static void shutdown() {
        provider = null;
    }

    /** Returns null when no NMS adapter supports packet seats on this server. */
    static PacketSeat open(Player rider, EntityType vehicle, Location location) {
        PacketSeat.Provider current = provider;
        return current == null ? null : current.open(rider, vehicle, location);
    }
}
