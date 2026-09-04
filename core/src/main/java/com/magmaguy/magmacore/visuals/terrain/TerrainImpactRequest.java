package com.magmaguy.magmacore.visuals.terrain;

import org.bukkit.Location;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable terrain-impact intent shared by Java and Lua callers.
 */
public record TerrainImpactRequest(
        Location center,
        double radius,
        double intensity,
        int durationTicks,
        double viewRange,
        long seed) {

    private static final double DEFAULT_RADIUS = 4.5;
    private static final double MIN_RADIUS = 0.5;
    private static final double MAX_RADIUS = 6.0;
    private static final double DEFAULT_INTENSITY = 1.0;
    private static final int DEFAULT_DURATION_TICKS = 35;
    private static final int MAX_DURATION_TICKS = 200;
    private static final double DEFAULT_VIEW_RANGE = 48.0;
    private static final double MIN_VIEW_RANGE = 1.0;
    private static final double MAX_VIEW_RANGE = 96.0;

    public TerrainImpactRequest {
        center = Objects.requireNonNull(center, "center").clone();
        radius = bounded(radius, DEFAULT_RADIUS, MIN_RADIUS, MAX_RADIUS);
        intensity = bounded(intensity, DEFAULT_INTENSITY, 0.0, 1.0);
        durationTicks = Math.max(1, Math.min(MAX_DURATION_TICKS, durationTicks));
        viewRange = bounded(viewRange, DEFAULT_VIEW_RANGE, MIN_VIEW_RANGE, MAX_VIEW_RANGE);
    }

    /**
     * Creates the default crack preset used by callers that do not specify visual tuning.
     */
    public TerrainImpactRequest(Location center) {
        this(center, DEFAULT_RADIUS, DEFAULT_INTENSITY, DEFAULT_DURATION_TICKS,
                DEFAULT_VIEW_RANGE, defaultSeed(center));
    }

    @Override
    public Location center() {
        return center.clone();
    }

    private static double bounded(double value, double fallback, double minimum, double maximum) {
        if (!Double.isFinite(value)) return fallback;
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long defaultSeed(Location center) {
        Objects.requireNonNull(center, "center");
        long seed = 0x9E3779B97F4A7C15L;
        if (center.getWorld() != null) {
            UUID worldId = center.getWorld().getUID();
            if (worldId != null) seed ^= worldId.getMostSignificantBits() ^ worldId.getLeastSignificantBits();
        }
        seed ^= (long) center.getBlockX() * 0x632BE59BD9B4E019L;
        seed ^= (long) center.getBlockY() * 0x9E3779B185EBCA87L;
        seed ^= (long) center.getBlockZ() * 0xC2B2AE3D27D4EB4FL;
        seed ^= seed >>> 30;
        seed *= 0xBF58476D1CE4E5B9L;
        seed ^= seed >>> 27;
        seed *= 0x94D049BB133111EBL;
        return seed ^ seed >>> 31;
    }
}
