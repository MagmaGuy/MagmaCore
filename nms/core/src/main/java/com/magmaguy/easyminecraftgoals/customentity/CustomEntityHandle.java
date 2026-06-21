package com.magmaguy.easyminecraftgoals.customentity;

import org.bukkit.Location;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Common runtime handle for packet-only and Bukkit-backed custom Bedrock
 * presentations.
 */
public interface CustomEntityHandle {
    CustomEntityDefinition definition();

    int getEntityId();

    Location getLocation();

    UUID getUniqueId();

    Set<UUID> getViewers();

    void setScale(float scale);

    void setColor(Integer color);

    void setVariant(Integer variant);

    void setHitbox(float width, float height);

    void setProperties(Map<String, ?> properties);

    void setProperty(String identifier, Object value);
}
