package com.magmaguy.easyminecraftgoals.mindshared.mind;

import com.magmaguy.easyminecraftgoals.PathfindingHandle;
import com.magmaguy.easyminecraftgoals.PathfindingStatus;
import com.magmaguy.easyminecraftgoals.internal.pathfinding.PathfindingDriver;
import com.magmaguy.easyminecraftgoals.internal.pathfinding.PathfindingSession;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import org.bukkit.Location;

/** Existing pathfinding session driven by the Mind's tick, with the same movement lease as flee. */
final class NativeMindPathfindingHandle implements PathfindingHandle, PathfindingDriver {
    private final NativeMindSession owner;
    private final Mob mob;
    private final PathfindingSession pathfinding;
    private final NativeMindMovementOverrideGate gate;
    private NativeMindMovementOverrideGate.Lease lease;
    private Path path;
    private boolean blocked, running;

    NativeMindPathfindingHandle(NativeMindSession owner, NativeMindBody body, NativeMindMovementOverrideGate gate) {
        this.owner = owner;
        this.mob = body.mob();
        this.gate = gate;
        pathfinding = new PathfindingSession(body.entity(), this);
    }

    void tick(boolean blocked) {
        this.blocked = blocked;
        if (running && !pathfinding.canContinueToUse()) pathfinding.stop();
        if (!running && pathfinding.canUse()) pathfinding.start();
        if (running) pathfinding.tick();
    }

    @Override public boolean moveTo(Location destination, double speedModifier) { return pathfinding.moveTo(destination, speedModifier); }
    @Override public void clearDestination() { pathfinding.clearDestination(); }
    @Override public PathfindingStatus status() { return pathfinding.status(); }
    @Override public void close() { pathfinding.close(); }
    @Override public boolean canNavigate() { return !blocked && mob.isAlive() && !mob.isRemoved(); }
    @Override public boolean hasCombatTarget() { return mob.getTarget() != null; }
    @Override public boolean preparePath(Location destination) {
        path = mob.getNavigation().createPath(destination.getX(), destination.getY(), destination.getZ(), 0);
        return path != null && path.canReach();
    }
    @Override public boolean startPreparedPath(double speedModifier) {
        if (path == null) return false;
        lease = gate.acquire();
        if (!mob.getNavigation().moveTo(path, speedModifier)) {
            lease.close(); lease = null; return false;
        }
        running = true;
        return true;
    }
    @Override public boolean navigationDone() { return mob.getNavigation().isDone(); }
    @Override public void stopNavigation() {
        if (lease != null) {
            mob.getNavigation().stop();
            NativeMindVersion.stopControl(mob.getMoveControl());
            lease.close(); lease = null;
        }
        running = false;
        path = null;
    }
    @Override public void unregisterGoal() { owner.releasePathfinding(this); }
}
