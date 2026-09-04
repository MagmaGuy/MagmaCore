package com.magmaguy.easyminecraftgoals.internal.pathfinding;

import com.magmaguy.easyminecraftgoals.PathfindingHandle;
import com.magmaguy.easyminecraftgoals.PathfindingStatus;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.Objects;

/**
 * Shared state machine behind each version-specific native pathfinding goal.
 *
 * <p>This class is intentionally scheduler-free. The native goal selector drives its lifecycle and
 * the owning plugin polls {@link #status()} to sequence higher-level movement.</p>
 */
public final class PathfindingSession implements PathfindingHandle {
    private static final double ARRIVAL_DISTANCE_SQUARED = 0.25D;
    private static final double PROGRESS_DISTANCE_SQUARED = 0.0025D;
    private static final int STUCK_TICKS = 100;

    private final LivingEntity livingEntity;
    private final PathfindingDriver driver;

    private Location destination;
    private Location lastProgressLocation;
    private double speedModifier;
    private int consecutiveStuckTicks;
    private boolean restartRequested;
    private boolean pathPrepared;
    private boolean closed;
    private PathfindingStatus status = PathfindingStatus.IDLE;

    public PathfindingSession(LivingEntity livingEntity, PathfindingDriver driver) {
        this.livingEntity = Objects.requireNonNull(livingEntity, "livingEntity");
        this.driver = Objects.requireNonNull(driver, "driver");
    }

    @Override
    public boolean moveTo(Location target, double requestedSpeedModifier) {
        if (closed || !isValidTarget(target) || !isValidSpeed(requestedSpeedModifier)) return false;
        if (!livingEntity.isValid() || livingEntity.isDead()) return false;

        destination = target.clone();
        speedModifier = requestedSpeedModifier;
        restartRequested = true;
        pathPrepared = false;
        resetProgress();
        driver.stopNavigation();
        status = PathfindingStatus.WAITING;
        return true;
    }

    @Override
    public void clearDestination() {
        if (closed) return;
        destination = null;
        restartRequested = false;
        pathPrepared = false;
        resetProgress();
        driver.stopNavigation();
        status = PathfindingStatus.IDLE;
    }

    @Override
    public PathfindingStatus status() {
        return status;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        destination = null;
        restartRequested = false;
        pathPrepared = false;
        resetProgress();
        status = PathfindingStatus.CLOSED;
        driver.stopNavigation();
        driver.unregisterGoal();
    }

    /** Called by the native goal's {@code canUse}. */
    public boolean canUse() {
        if (closed || destination == null) return false;
        if (!entityStillExists()) {
            finish(PathfindingStatus.ENDED_SHORT);
            return false;
        }
        if (!driver.canNavigate() || driver.hasCombatTarget()) {
            status = PathfindingStatus.WAITING;
            return false;
        }
        if (hasArrived()) {
            finish(PathfindingStatus.ARRIVED);
            return false;
        }

        restartRequested = false;
        pathPrepared = driver.preparePath(destination);
        if (!pathPrepared) {
            finish(PathfindingStatus.NO_PATH);
            return false;
        }
        return true;
    }

    /** Called by the native goal's {@code start}. */
    public void start() {
        if (closed || destination == null || !pathPrepared) return;
        if (!driver.startPreparedPath(speedModifier)) {
            finish(PathfindingStatus.NO_PATH);
            return;
        }
        status = PathfindingStatus.MOVING;
        lastProgressLocation = livingEntity.getLocation();
        consecutiveStuckTicks = 0;
    }

    /** Called by the native goal's {@code tick}. */
    public void tick() {
        if (closed || destination == null || status != PathfindingStatus.MOVING) return;
        if (hasArrived()) {
            finish(PathfindingStatus.ARRIVED);
            return;
        }

        Location current = livingEntity.getLocation();
        if (lastProgressLocation == null
                || lastProgressLocation.getWorld() != current.getWorld()
                || current.distanceSquared(lastProgressLocation) >= PROGRESS_DISTANCE_SQUARED) {
            lastProgressLocation = current;
            consecutiveStuckTicks = 0;
            return;
        }

        if (consecutiveStuckTicks < Integer.MAX_VALUE) consecutiveStuckTicks++;
        if (consecutiveStuckTicks >= STUCK_TICKS) finish(PathfindingStatus.STUCK);
    }

    /** Called by the native goal's {@code canContinueToUse}. */
    public boolean canContinueToUse() {
        if (closed || destination == null) return false;
        if (restartRequested) {
            status = PathfindingStatus.WAITING;
            return false;
        }
        if (!entityStillExists()) {
            finish(PathfindingStatus.ENDED_SHORT);
            return false;
        }
        if (!driver.canNavigate() || driver.hasCombatTarget()) {
            status = PathfindingStatus.WAITING;
            return false;
        }
        if (hasArrived()) {
            finish(PathfindingStatus.ARRIVED);
            return false;
        }
        if (status != PathfindingStatus.MOVING) return false;
        if (driver.navigationDone()) {
            finish(PathfindingStatus.ENDED_SHORT);
            return false;
        }
        return true;
    }

    /** Called by the native goal's {@code stop}. */
    public void stop() {
        driver.stopNavigation();
        pathPrepared = false;
        resetProgress();
        if (!closed && destination != null && status == PathfindingStatus.MOVING) {
            status = PathfindingStatus.WAITING;
        }
    }

    private boolean entityStillExists() {
        return livingEntity.isValid() && !livingEntity.isDead();
    }

    private boolean hasArrived() {
        if (destination == null || livingEntity.getWorld() != destination.getWorld()) return false;
        return livingEntity.getLocation().distanceSquared(destination) <= ARRIVAL_DISTANCE_SQUARED;
    }

    private void finish(PathfindingStatus terminalStatus) {
        destination = null;
        restartRequested = false;
        pathPrepared = false;
        resetProgress();
        status = terminalStatus;
        driver.stopNavigation();
    }

    private void resetProgress() {
        lastProgressLocation = null;
        consecutiveStuckTicks = 0;
    }

    private boolean isValidTarget(Location target) {
        return target != null
                && target.getWorld() != null
                && target.getWorld() == livingEntity.getWorld()
                && Double.isFinite(target.getX())
                && Double.isFinite(target.getY())
                && Double.isFinite(target.getZ());
    }

    private static boolean isValidSpeed(double requestedSpeedModifier) {
        return Double.isFinite(requestedSpeedModifier) && requestedSpeedModifier > 0D;
    }
}
