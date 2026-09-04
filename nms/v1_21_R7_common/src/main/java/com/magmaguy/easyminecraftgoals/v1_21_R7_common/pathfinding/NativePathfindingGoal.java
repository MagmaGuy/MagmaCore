package com.magmaguy.easyminecraftgoals.v1_21_R7_common.pathfinding;

import com.magmaguy.easyminecraftgoals.PathfindingHandle;
import com.magmaguy.easyminecraftgoals.PathfindingStatus;
import com.magmaguy.easyminecraftgoals.internal.pathfinding.PathfindingDriver;
import com.magmaguy.easyminecraftgoals.internal.pathfinding.PathfindingSession;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.EnumSet;

/** Native goal wrapper for one reusable MagmaCore pathfinding session. */
public final class NativePathfindingGoal extends Goal implements PathfindingHandle, PathfindingDriver {
    private final PathfinderMob mob;
    private final int priority;
    private final PathfindingSession session;
    private Path path;

    public NativePathfindingGoal(PathfinderMob mob, LivingEntity livingEntity, int priority) {
        this.mob = mob;
        this.priority = priority;
        this.session = new PathfindingSession(livingEntity, this);
        setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
    }

    public void register() {
        mob.goalSelector.addGoal(priority, this);
    }

    @Override public boolean canUse() { return session.canUse(); }
    @Override public boolean canContinueToUse() { return session.canContinueToUse(); }
    @Override public void start() { session.start(); }
    @Override public void tick() { session.tick(); }
    @Override public void stop() { session.stop(); }
    @Override public boolean isInterruptable() { return true; }
    @Override public boolean moveTo(Location target, double speedModifier) { return session.moveTo(target, speedModifier); }
    @Override public void clearDestination() { session.clearDestination(); }
    @Override public PathfindingStatus status() { return session.status(); }
    @Override public void close() { session.close(); }
    @Override public boolean canNavigate() { return mob.isAlive() && !mob.isRemoved() && !mob.isNoAi(); }
    @Override public boolean hasCombatTarget() { return mob.getTarget() != null; }

    @Override
    public boolean preparePath(Location destination) {
        double dx = destination.getX() - mob.getX();
        double dy = destination.getY() - mob.getY();
        double dz = destination.getZ() - mob.getZ();
        var navigation = mob.getNavigation();
        navigation.setRequiredPathLength((float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        try {
            path = navigation.createPath(destination.getX(), destination.getY(), destination.getZ(), 0);
        } finally {
            navigation.setRequiredPathLength(0F);
        }
        return path != null && path.canReach();
    }

    @Override
    public boolean startPreparedPath(double speedModifier) {
        return path != null && mob.getNavigation().moveTo(path, speedModifier);
    }

    @Override public boolean navigationDone() { return mob.getNavigation().isDone(); }

    @Override
    public void stopNavigation() {
        mob.getNavigation().stop();
        path = null;
    }

    @Override public void unregisterGoal() { mob.goalSelector.removeGoal(this); }
}
