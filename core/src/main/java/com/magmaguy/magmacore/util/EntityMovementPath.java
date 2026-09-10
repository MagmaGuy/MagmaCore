package com.magmaguy.magmacore.util;

import com.magmaguy.magmacore.location.LocationQueryRegistry;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Optional collision/protection preflight for authored pulls. Does not move an entity or load chunks. */
public final class EntityMovementPath {
    public enum Status { CLEAR, BLOCKED, PROTECTED, UNAVAILABLE }
    private EntityMovementPath() { }

    public static boolean isClear(Entity entity, Location destination) {
        return inspect(entity, destination) == Status.CLEAR;
    }

    public static Status inspect(Entity entity, Location destination) {
        if (entity == null || !entity.isValid() || entity.isDead() || destination == null) return Status.UNAVAILABLE;
        World world = entity.getWorld();
        if (!world.equals(destination.getWorld())) return Status.UNAVAILABLE;
        Vector offset = destination.toVector().subtract(entity.getLocation().toVector());
        double distance = offset.length();
        if (!Double.isFinite(distance)) return Status.UNAVAILABLE;
        BoundingBox source = entity.getBoundingBox();
        BoundingBox end = source.clone().shift(offset);
        if (Math.min(source.getMinY(), end.getMinY()) < world.getMinHeight()
                || Math.max(source.getMaxY(), end.getMaxY()) > world.getMaxHeight()
                || !world.getWorldBorder().isInside(destination)) return Status.UNAVAILABLE;

        // Quarter-block steps keep the per-step swept volume small even on diagonal pulls.
        // Each segment tests a swept box, so thin blocks cannot fall between sample points.
        int steps = Math.max(1, (int) Math.ceil(distance * 4));
        Vector step = offset.clone().multiply(1D / steps);
        BoundingBox previous = source;
        for (int i = 1; i <= steps; i++) {
            BoundingBox next = source.clone().shift(step.clone().multiply(i));
            BoundingBox swept = previous.clone().union(next).expand(-1.0E-5D);
            Status status = inspectVolume(world, swept);
            if (status != Status.CLEAR) return status;
            previous = next;
        }
        return Status.CLEAR;
    }

    private static Status inspectVolume(World world, BoundingBox volume) {
        int minX = (int) Math.floor(volume.getMinX()), maxX = (int) Math.floor(volume.getMaxX());
        int minY = (int) Math.floor(volume.getMinY()), maxY = (int) Math.floor(volume.getMaxY());
        int minZ = (int) Math.floor(volume.getMinZ()), maxZ = (int) Math.floor(volume.getMaxZ());
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return Status.UNAVAILABLE;
            // Fences/walls can extend above the block cell immediately below the entity.
            for (int y = Math.max(world.getMinHeight(), minY - 1); y <= maxY; y++) {
                var block = world.getBlockAt(x, y, z);
                if (y >= minY && LocationQueryRegistry.isInAnyProtectedRegion(block.getLocation())) return Status.PROTECTED;
                // Bukkit's enclosing collision box is conservative around stairs and fences.
                // Refusing a narrow route is preferable to pulling through an obstruction.
                if (!block.isPassable() && block.getBoundingBox().overlaps(volume)) return Status.BLOCKED;
            }
        }
        return Status.CLEAR;
    }
}
