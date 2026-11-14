package com.magmaguy.easyminecraftgoals.v1_21_R4.move;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.pathfinder.Path;
import org.bukkit.Location;

public class Move {
    public static boolean canReach(PathfinderMob pathfinderMob, Location destination) {
        if (pathfinderMob.getNavigation() == null) return true;
        Path path = pathfinderMob.getNavigation().createPath(destination.getX(), destination.getY(), destination.getZ(), 0);
        if (path == null) return true;
        return path.canReach();
    }

    public static boolean simpleMove(PathfinderMob pathfinderMob, double speedModifier, Location destination) {
        Path path = pathfinderMob.getNavigation().createPath(destination.getX(), destination.getY(), destination.getZ(), 0);
        return pathfinderMob.getNavigation().moveTo(path, speedModifier);
    }

    public static void doNotMove(PathfinderMob pathfinderMob) {
        pathfinderMob.getNavigation().moveTo(pathfinderMob,0);
    }
}
