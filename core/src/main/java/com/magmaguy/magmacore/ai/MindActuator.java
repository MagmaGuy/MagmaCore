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
     * Walks, or swims, straight at the destination at speedModifier times the body's movement speed, planning no
     * path: for the last stretch to an exact spot, where {@link #moveTo}'s path ends about a block short. Nothing steers
     * the body round what stands in the way, so call it only with a clear line, and again every tick. Requires the MOVE
     * lease; false for a flying or stationary body, or while movement is overridden.
     */
    default boolean moveDirect(Location destination, double speedModifier) { return false; }

    /**
     * One-tick native flight velocity in blocks/tick, scaled down to {@link #MAX_FLIGHT_SPEED} when faster;
     * requires MOVE and LOOK leases and a flying body.
     */
    default boolean steerFlight(org.bukkit.util.Vector velocity) { return false; }

    /**
     * Runs from a threat: plans a path of about ten blocks that leads away from it and follows it at speedModifier
     * times the body's movement speed. Planning tries several ways out, so call it every few ticks, not every tick;
     * the path is followed in between. Requires the MOVE lease; false when the body cannot plan paths, finds no way
     * out, or its movement is overridden.
     */
    default boolean moveAwayFrom(Location threat, double speedModifier) { return false; }

    void stopMoving();

    void lookAt(Location destination);

    void jump();

    void setTarget(LivingEntity target);

    void clearTarget();

    /**
     * Hits the target the way the body's own kind does: a swing that deals the body's attack damage, or 2 for a
     * kind that has none, such as a chicken. A borrowed creeper swells instead, for as long as this is called every
     * tick, and explodes once it has swollen all the way; a tick without the call and it deflates. Requires the
     * ATTACK lease.
     */
    void attack(LivingEntity target);

    /** A swing that deals this much damage, credited to the body. Requires the ATTACK lease. */
    default void attack(LivingEntity target, double damage) { attack(target); }

    /**
     * Shoots at the target as the body's own kind shoots: a skeleton's arrow, a pillager's crossbow (loaded first
     * when it is empty), a witch's potion, a llama's spit. Requires the ATTACK lease; false for a kind that has no
     * shot of its own.
     */
    default boolean rangedAttack(LivingEntity target) { return false; }

    void stopAll();
}
