package com.magmaguy.magmacore.ai;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

/**
 * Version-independent control seam implemented by the active native body.
 */
public interface MindActuator {
    boolean moveTo(Location destination, double speedModifier);

    void stopMoving();

    void lookAt(Location destination);

    void jump();

    void setTarget(LivingEntity target);

    void clearTarget();

    void attack(LivingEntity target);

    void stopAll();
}
