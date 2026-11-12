package com.magmaguy.easyminecraftgoals.mindshared.mind;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * What a native Mind session asks of whatever moves its body: the controls installed on a carrier the host made
 * ({@link NativeMindBodyControl}), or the hold on a mob it borrowed ({@link NativeMindBorrowedControl}).
 */
interface NativeMindBodyDriver {
    /** A session took the body. */
    default void attached() {
    }

    /** The session let the body go. */
    default void detached() {
    }

    void beforeMindTick();

    void afterMindTick();

    void stopMovement();

    void tickPausedPhysics();

    /** Hands the body to vanilla until the next Mind tick takes it back. */
    void release();

    void steerFlight(Vec3 velocity);

    /**
     * Attacks the target in a way of the body's own that a melee hit is not, such as a creeper's swelling. False
     * leaves the hit to the actuator.
     */
    default boolean attack(LivingEntity target) {
        return false;
    }
}
