package com.magmaguy.easyminecraftgoals.mindshared.flee;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.pathfinder.Path;
import org.bukkit.Location;

import java.util.Objects;

/** Version-specific path selection shared by ordinary goals and native Mind bodies. */
public final class FleePathfinder {
    private static final double PATH_DISTANCE = 10D;

    private FleePathfinder() {
    }

    public static Path findPath(PathfinderMob mob, Location threatLocation) {
        Objects.requireNonNull(mob, "mob");
        Objects.requireNonNull(threatLocation, "threatLocation");
        for (FleeDestinationPlanner.Destination destination : FleeDestinationPlanner.candidates(
                mob.getX(),
                mob.getY(),
                mob.getZ(),
                threatLocation.getX(),
                threatLocation.getY(),
                threatLocation.getZ(),
                PATH_DISTANCE)) {
            Path path = mob.getNavigation().createPath(
                    destination.x(), destination.y(), destination.z(), 0);
            if (path != null && path.canReach()) return path;
        }
        return null;
    }
}
