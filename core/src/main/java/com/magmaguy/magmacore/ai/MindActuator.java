package com.magmaguy.magmacore.ai;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

/**
 * Version-independent control seam implemented by the active native body.
 */
public interface MindActuator {
    /**
     * Fastest flight a Mind can steer, in blocks per tick. Native bodies scale a faster {@link #steerFlight}
     * velocity down to this length, keeping its direction. That is far beyond any vanilla flyer, and short enough
     * that the collision sweep over one tick's travel stays close to the body.
     */
    double MAX_FLIGHT_SPEED = 4.0D;

    boolean moveTo(Location destination, double speedModifier);

    /**
     * One-tick native flight velocity in blocks/tick, scaled down to {@link #MAX_FLIGHT_SPEED} when faster;
     * requires MOVE and LOOK leases and a flying body.
     */
    default boolean steerFlight(org.bukkit.util.Vector velocity) { return false; }

    void stopMoving();

    void lookAt(Location destination);

    void jump();

    void setTarget(LivingEntity target);

    void clearTarget();

    void attack(LivingEntity target);

    void stopAll();
}
