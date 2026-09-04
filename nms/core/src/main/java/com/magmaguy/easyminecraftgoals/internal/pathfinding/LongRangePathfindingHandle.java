package com.magmaguy.easyminecraftgoals.internal.pathfinding;

import com.magmaguy.easyminecraftgoals.PathfindingHandle;
import com.magmaguy.easyminecraftgoals.PathfindingStatus;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Deep navigation module over one native single-destination handle. It owns snapshot capture,
 * asynchronous long-range planning, rolling horizons, bounded native legs, and replanning.
 */
public final class LongRangePathfindingHandle implements PathfindingHandle {
    private static final double ARRIVAL_DISTANCE_SQUARED = 0.25D;
    private static final long TERRAIN_RETRY_MILLIS = 1_000L;
    private static final int MAX_INTERNAL_REPLANS = 2;

    private final LivingEntity livingEntity;
    private final PathfindingHandle nativeHandle;
    private final Deque<Location> pendingWaypoints = new ArrayDeque<>();

    private LongRangePathPlanner.Request planningRequest;
    private Location finalTarget;
    private Location activeWaypoint;
    private List<Location> preview = List.of();
    private double speedModifier;
    private long terrainRetryAfterMillis;
    private int internalReplans;
    private boolean currentPlanReachesFinal;
    private boolean closed;
    private PathfindingStatus status = PathfindingStatus.IDLE;

    public LongRangePathfindingHandle(LivingEntity livingEntity, PathfindingHandle nativeHandle) {
        this.livingEntity = java.util.Objects.requireNonNull(livingEntity, "livingEntity");
        this.nativeHandle = java.util.Objects.requireNonNull(nativeHandle, "nativeHandle");
    }

    @Override
    public boolean moveTo(Location target, double requestedSpeedModifier) {
        requirePrimaryThread();
        if (closed || !validTarget(target) || !validSpeed(requestedSpeedModifier) || !bodyExists()) return false;
        resetRequest();
        finalTarget = target.clone();
        speedModifier = requestedSpeedModifier;
        internalReplans = 0;
        terrainRetryAfterMillis = 0L;

        Location start = livingEntity.getLocation();
        if (start.distance(target) <= LongRangePathPlanner.NATIVE_LEG_DISTANCE) {
            preview = List.of(start.clone(), target.clone());
            return issueNative(target);
        }
        beginPlanning();
        return true;
    }

    @Override
    public void clearDestination() {
        requirePrimaryThread();
        if (closed) return;
        resetRequest();
        nativeHandle.clearDestination();
        status = PathfindingStatus.IDLE;
    }

    @Override
    public PathfindingStatus status() {
        requirePrimaryThread();
        pump();
        return status;
    }

    @Override
    public List<Location> routePreview() {
        requirePrimaryThread();
        pump();
        return preview.stream().map(Location::clone).toList();
    }

    @Override
    public void close() {
        requirePrimaryThread();
        if (closed) return;
        closed = true;
        cancelPlanning();
        pendingWaypoints.clear();
        finalTarget = null;
        activeWaypoint = null;
        preview = List.of();
        nativeHandle.close();
        status = PathfindingStatus.CLOSED;
    }

    private void pump() {
        if (closed) return;
        if (!bodyExists()) {
            finish(PathfindingStatus.ENDED_SHORT);
            return;
        }
        if (planningRequest != null) return;
        if (terrainRetryAfterMillis > 0L) {
            if (System.currentTimeMillis() >= terrainRetryAfterMillis && finalTarget != null) beginPlanning();
            return;
        }
        if (activeWaypoint == null) return;

        PathfindingStatus nativeStatus = nativeHandle.status();
        switch (nativeStatus) {
            case MOVING, WAITING -> status = nativeStatus;
            case ARRIVED -> {
                activeWaypoint = null;
                internalReplans = 0;
                issueNextWaypoint();
            }
            case NO_PATH, ENDED_SHORT, STUCK -> nativeFailure(nativeStatus);
            case CLOSED -> close();
            case IDLE -> issueNative(activeWaypoint);
        }
    }

    private void nativeFailure(PathfindingStatus failure) {
        nativeHandle.clearDestination();
        activeWaypoint = null;
        if (finalTarget != null && internalReplans++ < MAX_INTERNAL_REPLANS) {
            beginPlanning();
            return;
        }
        finish(failure);
    }

    private void beginPlanning() {
        if (closed || finalTarget == null || !bodyExists()) {
            finish(PathfindingStatus.ENDED_SHORT);
            return;
        }
        cancelPlanning();
        terrainRetryAfterMillis = 0L;
        nativeHandle.clearDestination();
        pendingWaypoints.clear();
        activeWaypoint = null;
        preview = List.of();
        currentPlanReachesFinal = false;
        status = PathfindingStatus.WAITING;
        planningRequest = LongRangePathPlanner.plan(livingEntity, finalTarget, this::planReady);
    }

    private void planReady(LongRangePathPlanner.PlanResult result) {
        requirePrimaryThread();
        if (closed || finalTarget == null) return;
        planningRequest = null;
        switch (result.outcome()) {
            case FOUND -> {
                preview = result.preview();
                pendingWaypoints.clear();
                result.waypoints().stream().map(Location::clone).forEach(pendingWaypoints::addLast);
                currentPlanReachesFinal = result.reachesFinal();
                issueNextWaypoint();
            }
            case TERRAIN_UNAVAILABLE -> {
                status = PathfindingStatus.WAITING;
                terrainRetryAfterMillis = System.currentTimeMillis() + TERRAIN_RETRY_MILLIS;
            }
            case NO_PATH -> finish(PathfindingStatus.NO_PATH);
        }
    }

    private void issueNextWaypoint() {
        while (!pendingWaypoints.isEmpty()
                && livingEntity.getLocation().distanceSquared(pendingWaypoints.getFirst()) <= ARRIVAL_DISTANCE_SQUARED) {
            pendingWaypoints.removeFirst();
        }
        if (!pendingWaypoints.isEmpty()) {
            issueNative(pendingWaypoints.removeFirst());
            return;
        }
        if (currentPlanReachesFinal
                && finalTarget != null
                && livingEntity.getLocation().distanceSquared(finalTarget) <= ARRIVAL_DISTANCE_SQUARED) {
            finish(PathfindingStatus.ARRIVED);
            return;
        }
        if (currentPlanReachesFinal && finalTarget != null) {
            issueNative(finalTarget);
            return;
        }
        beginPlanning();
    }

    private boolean issueNative(Location waypoint) {
        if (waypoint == null || closed) return false;
        activeWaypoint = waypoint.clone();
        boolean accepted = nativeHandle.moveTo(activeWaypoint, speedModifier);
        if (accepted) {
            status = PathfindingStatus.WAITING;
            return true;
        }
        activeWaypoint = null;
        finish(PathfindingStatus.NO_PATH);
        return false;
    }

    private void finish(PathfindingStatus terminalStatus) {
        cancelPlanning();
        pendingWaypoints.clear();
        finalTarget = null;
        activeWaypoint = null;
        currentPlanReachesFinal = false;
        terrainRetryAfterMillis = 0L;
        if (!closed && terminalStatus != PathfindingStatus.CLOSED) nativeHandle.clearDestination();
        status = terminalStatus;
    }

    private void resetRequest() {
        cancelPlanning();
        pendingWaypoints.clear();
        finalTarget = null;
        activeWaypoint = null;
        preview = List.of();
        currentPlanReachesFinal = false;
        terrainRetryAfterMillis = 0L;
    }

    private void cancelPlanning() {
        if (planningRequest != null) planningRequest.close();
        planningRequest = null;
    }

    private boolean validTarget(Location target) {
        return target != null
                && target.getWorld() != null
                && target.getWorld() == livingEntity.getWorld()
                && Double.isFinite(target.getX())
                && Double.isFinite(target.getY())
                && Double.isFinite(target.getZ());
    }

    private boolean bodyExists() {
        return livingEntity.isValid() && !livingEntity.isDead();
    }

    private static boolean validSpeed(double speed) {
        return Double.isFinite(speed) && speed > 0D;
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("PathfindingHandle methods must run on the server thread");
        }
    }

}
