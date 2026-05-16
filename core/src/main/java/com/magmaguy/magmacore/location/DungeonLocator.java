package com.magmaguy.magmacore.location;

import org.bukkit.Location;

/**
 * Implemented by plugins that track dungeon regions. Registered with
 * {@link LocationQueryRegistry} so that scripts and other plugins can ask
 * whether a given location is inside any dungeon without taking a hard
 * dependency on the plugin that owns the dungeon data.
 */
@FunctionalInterface
public interface DungeonLocator {
    boolean contains(Location location);
}
