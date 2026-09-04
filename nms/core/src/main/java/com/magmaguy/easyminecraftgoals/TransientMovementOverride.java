package com.magmaguy.easyminecraftgoals;

import org.bukkit.Location;

/**
 * A temporary, adapter-owned movement objective which can be redirected without rebuilding the
 * entity's permanent AI. Implementations must make {@link #close()} idempotent and remove only
 * state that they installed.
 */
public interface TransientMovementOverride extends AutoCloseable {
    boolean isActive();

    /**
     * Redirects the movement objective to the current threat location.
     *
     * @return false when the override can no longer continue safely
     */
    boolean retarget(Location threatLocation);

    @Override
    void close();
}
