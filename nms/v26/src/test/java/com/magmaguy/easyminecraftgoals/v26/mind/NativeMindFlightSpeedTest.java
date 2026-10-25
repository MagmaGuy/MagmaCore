package com.magmaguy.easyminecraftgoals.v26.mind;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The flight cap lives in nms/mind-shared; this adapter compiles that source, so its tests cover it. */
class NativeMindFlightSpeedTest {
    private static final double EPSILON = 1.0E-9D;

    @Test
    void aVelocityWithinTheCapIsUnchanged() {
        assertEquals(new Vector(0.5D, -0.2D, 0.1D), NativeMindActuator.cappedFlight(new Vector(0.5D, -0.2D, 0.1D)));
    }

    @Test
    void aFasterVelocityKeepsItsDirectionAtTheCap() {
        Vector capped = NativeMindActuator.cappedFlight(new Vector(30D, 40D, 120D));
        assertEquals(NativeMindActuator.MAX_FLIGHT_SPEED, capped.length(), EPSILON);
        assertEquals(3D / 13D, capped.getX() / capped.length(), EPSILON);
        assertEquals(4D / 13D, capped.getY() / capped.length(), EPSILON);
        assertEquals(12D / 13D, capped.getZ() / capped.length(), EPSILON);
    }
}
