package com.magmaguy.easyminecraftgoals.internal.pathfinding;

import org.bukkit.Location;

/**
 * Version-specific operations required by {@link PathfindingSession}.
 *
 * <p>The driver owns the native goal registration and prepared path. The session owns every
 * lifecycle and status transition so adapter versions cannot drift semantically.</p>
 */
public interface PathfindingDriver {

    /** Returns whether the native entity can currently navigate. */
    boolean canNavigate();

    /** Returns whether combat currently owns movement. */
    boolean hasCombatTarget();

    /** Creates and retains a complete native path to the destination. */
    boolean preparePath(Location destination);

    /** Starts following the retained path. */
    boolean startPreparedPath(double speedModifier);

    /** Returns whether native navigation has stopped following the retained path. */
    boolean navigationDone();

    /** Stops navigation and discards any retained path. */
    void stopNavigation();

    /** Removes the native goal installed for this session. */
    void unregisterGoal();
}
