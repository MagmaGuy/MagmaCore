package com.magmaguy.magmacore.location;

import org.bukkit.Location;

/**
 * Implemented by adapters for region/claim plugins (WorldGuard, GriefPrevention, etc.).
 * Registered with {@link LocationQueryRegistry} so scripts and other plugins can ask
 * "is this location protected?" without knowing which region plugin is installed.
 */
public interface RegionProtectionProvider {
    boolean isProtected(Location location);

    String providerName();
}
