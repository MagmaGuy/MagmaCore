package com.magmaguy.easyminecraftgoals.internal;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Tracks text at a moving anchor without making it a passenger. Minecraft hides
 * ordinary living-entity names when they carry passengers, including packet-only ones.
 * Owns the supplied text until closed; use the existing tracker for viewer lifecycle.
 */
public final class FollowingText implements TrackedPacketEntity, AutoCloseable {
    private final UUID id = UUID.randomUUID();
    private final FakeText text;
    private final Entity anchor;
    private final Supplier<Location> position;
    private final Predicate<Player> viewerFilter;
    private final Set<UUID> viewers = new HashSet<>();
    private Location location;
    private boolean closed;

    public FollowingText(FakeText text, Entity anchor, Supplier<Location> position,
                         Predicate<Player> viewerFilter) {
        this.text = text;
        this.anchor = anchor;
        this.position = position;
        this.viewerFilter = viewerFilter;
        this.location = position.get().clone();
        text.teleport(location);
        PacketEntityTracker.getInstance().register(this);
    }

    @Override
    public void updateTracking() {
        if (!isValid()) {
            close();
            return;
        }
        Location next = position.get();
        if (!next.getWorld().equals(location.getWorld())) {
            for (UUID viewer : Set.copyOf(viewers)) text.hideFrom(viewer);
            viewers.clear();
        }
        if (!next.equals(location)) {
            location = next.clone();
            text.teleport(location);
        }
    }

    @Override public Location getTrackingLocation() { return location.clone(); }
    @Override public World getWorld() { return location.getWorld(); }
    @Override public void showToPlayer(Player player) {
        text.displayTo(player);
        viewers.add(player.getUniqueId());
    }
    @Override public void hideFromPlayer(Player player) {
        text.hideFrom(player);
        viewers.remove(player.getUniqueId());
    }
    @Override public boolean isVisibleTo(Player player) { return viewers.contains(player.getUniqueId()); }
    @Override public boolean canBeSeenBy(Player player) { return viewerFilter.test(player); }
    @Override public Set<UUID> getCurrentViewers() { return Set.copyOf(viewers); }
    @Override public boolean isValid() { return !closed && anchor.isValid(); }
    @Override public UUID getUniqueId() { return id; }
    @Override public Entity getVehicle() { return null; }
    @Override public void remount() { }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        PacketEntityTracker.getInstance().unregister(this);
        text.remove();
        viewers.clear();
    }
}
