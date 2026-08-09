package com.magmaguy.easyminecraftgoals.internal;

import com.magmaguy.easyminecraftgoals.events.WanderBackToPointEndEvent;
import com.magmaguy.easyminecraftgoals.events.WanderBackToPointStartEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

/**
 * Tracks one wander-back execution and keeps its Bukkit event lifecycle balanced.
 */
public final class WanderBackToPointState {

    private final AbstractWanderBackToPoint wanderBackToPoint;
    private boolean active;
    private long startedAtMillis;

    public WanderBackToPointState(AbstractWanderBackToPoint wanderBackToPoint) {
        this.wanderBackToPoint = wanderBackToPoint;
    }

    public boolean begin() {
        if (active) return false;

        active = true;
        startedAtMillis = System.currentTimeMillis();
        WanderBackToPointStartEvent event = new WanderBackToPointStartEvent(
                wanderBackToPoint.isHardObjective(),
                wanderBackToPoint.getLivingEntity(),
                wanderBackToPoint);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) return true;

        active = false;
        startedAtMillis = 0;
        return false;
    }

    public void end(boolean teleportOnFailure) {
        if (!active) return;

        active = false;
        startedAtMillis = 0;
        LivingEntity livingEntity = wanderBackToPoint.getLivingEntity();
        if (teleportOnFailure && wanderBackToPoint.isTeleportOnFail() &&
                livingEntity != null && livingEntity.isValid() && !livingEntity.isDead())
            livingEntity.teleport(wanderBackToPoint.getReturnLocation());

        WanderBackToPointEndEvent event = new WanderBackToPointEndEvent(
                wanderBackToPoint.isHardObjective(),
                livingEntity,
                wanderBackToPoint);
        Bukkit.getPluginManager().callEvent(event);
    }

    public boolean isActive() {
        return active;
    }

    public boolean hasTimedOut() {
        return active && System.currentTimeMillis() - startedAtMillis >= 50L * wanderBackToPoint.getMaxDurationTicks();
    }

    public boolean isAtReturnPoint() {
        LivingEntity livingEntity = wanderBackToPoint.getLivingEntity();
        Location returnLocation = wanderBackToPoint.getReturnLocation();
        if (livingEntity == null || returnLocation == null || returnLocation.getWorld() == null) return false;

        Location currentLocation = livingEntity.getLocation();
        if (currentLocation.getWorld() == null || !currentLocation.getWorld().equals(returnLocation.getWorld())) return false;

        double acceptedDistance = Math.max(1D, wanderBackToPoint.getStopReturnDistance());
        return currentLocation.distanceSquared(returnLocation) <= acceptedDistance * acceptedDistance;
    }
}
