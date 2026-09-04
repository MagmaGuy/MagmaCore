package com.magmaguy.magmacore.ai;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Optional;

/** Read-only native perception available to sensors and behaviors. */
public interface MindPerception {
    Optional<LivingEntity> currentTarget();

    Optional<Player> nearestPlayer(double maxDistance);

    boolean lineOfSight(LivingEntity target);

    /** Native movement progress for the active MOVE request. */
    default MindNavigationStatus navigation() {
        return MindNavigationStatus.idle();
    }
}
