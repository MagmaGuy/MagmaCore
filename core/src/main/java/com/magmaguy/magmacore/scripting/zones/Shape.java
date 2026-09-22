package com.magmaguy.magmacore.scripting.zones;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;

import java.util.List;

public abstract class Shape {
    public abstract boolean contains(Location position);

    public abstract boolean contains(LivingEntity livingEntity);

    public abstract boolean borderContains(Location position);

    public abstract boolean borderContains(LivingEntity livingEntity);

    public abstract void visualize(Particle particle);

    public abstract Location getCenter();

    /**
     * Returns fresh, conservative bounds for entity candidate selection, or null when a world scan
     * is required. Every entity accepted by either body predicate must have an AABB intersecting
     * these bounds, including a touching boundary. Callers must expand outward before a strict
     * overlap query and still apply the exact predicate. Subclasses that change membership must
     * preserve this guarantee or return null. Bounds describe current geometry, never cached members.
     */
    public BoundingBox getEntityQueryBounds() {
        // Sphere/Dome use an eye-height surrogate that can extend outside the entity's real AABB.
        return null;
    }

    public abstract List<Location> getEdgeLocations();

    public abstract List<Location> getLocations();
}
