package com.magmaguy.easyminecraftgoals.internal;

import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.magmacore.rails.PacketSeat;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Shows a packet-only vehicle to nearby players with the rider seated on it. */
public final class PacketSeatImpl implements PacketSeat {
    private static final double VIEW_DISTANCE_SQUARED = 48 * 48;
    private static final int VIEWER_REFRESH_TICKS = 10;
    private final NMSAdapter adapter;
    private final Player rider;
    private final PacketEntityInterface vehicle;
    private final Set<UUID> viewers = new HashSet<>();
    private int ticks;
    private boolean closed;

    public PacketSeatImpl(NMSAdapter adapter, Player rider, EntityType type, Location location) {
        this.adapter = adapter;
        this.rider = rider;
        vehicle = adapter.createPacketEntity(type, location);
        vehicle.teleport(location);
        refreshViewers();
    }

    @Override
    public void move(Location location) {
        if (closed) return;
        vehicle.teleport(location);
        adapter.setServerPosition(rider, location);
        if (++ticks % VIEWER_REFRESH_TICKS == 0) refreshViewers();
    }

    private void refreshViewers() {
        Location at = vehicle.getLocation();
        Set<UUID> current = new HashSet<>();
        for (Player player : rider.getWorld().getPlayers()) {
            if (!player.equals(rider) && player.getLocation().distanceSquared(at) > VIEW_DISTANCE_SQUARED) continue;
            current.add(player.getUniqueId());
            boolean added = viewers.add(player.getUniqueId());
            if (added) vehicle.displayTo(player.getUniqueId());
            // The rider's client is seated once. Other clients can start tracking the rider after the
            // vehicle, and a passenger list naming an entity they do not know yet seats nothing.
            if (added || !player.equals(rider))
                adapter.sendPacketPassengers(player, vehicle.getEntityId(), new int[]{rider.getEntityId()});
        }
        for (UUID viewer : new HashSet<>(viewers))
            if (!current.contains(viewer)) {
                vehicle.hideFrom(viewer);
                viewers.remove(viewer);
            }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        vehicle.remove();
        viewers.clear();
    }
}
