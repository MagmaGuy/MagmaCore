package com.magmaguy.easyminecraftgoals;

import org.bukkit.Location;

import java.util.List;

/**
 * Adapter-owned, single-destination native navigation.
 *
 * <p>The handle installs one interruptible MOVE/JUMP goal on an existing mob. Callers own route
 * sequencing and lifecycle policy; the handle owns path creation, goal registration, progress
 * detection and native cleanup. All methods must be called from the server thread. Active handles
 * must have {@link #status()} polled once per tick so rolling long-range plans can advance.</p>
 */
public interface PathfindingHandle extends AutoCloseable {

    /**
     * Replaces the current destination.
     *
     * @param target destination in the entity's current world
     * @param speedModifier multiplier applied to the entity's movement-speed attribute; 1.0 is normal speed
     * @return false when the request is invalid or the handle is already closed
     */
    boolean moveTo(Location target, double speedModifier);

    /** Clears the destination and stops only the native navigation installed by this handle. */
    void clearDestination();

    /** Returns the latest state observed by the native goal. */
    PathfindingStatus status();

    /**
     * Returns the current resolved route for diagnostics and authoring previews.
     * The list is empty while no long-range plan is available.
     */
    default List<Location> routePreview() {
        return List.of();
    }

    /** Idempotently unregisters the goal and releases native references. */
    @Override
    void close();
}
