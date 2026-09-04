package com.magmaguy.easyminecraftgoals.v26.flee;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FleeDestinationPlannerTest {
    @Test
    void candidatesAlwaysIncreaseHorizontalDistanceFromThreat() {
        List<FleeDestinationPlanner.Destination> candidates =
                FleeDestinationPlanner.candidates(4D, 12D, -2D, 1D, 40D, -2D, 10D);

        double initialDistanceSquared = 9D;
        assertEquals(7, candidates.size());
        assertTrue(candidates.stream().allMatch(candidate ->
                horizontalDistanceSquared(candidate.x(), candidate.z(), 1D, -2D)
                        > initialDistanceSquared));
        assertTrue(candidates.stream().allMatch(candidate -> candidate.y() == 12D));
    }

    @Test
    void coincidentThreatUsesDeterministicPositiveZFallback() {
        FleeDestinationPlanner.Destination first =
                FleeDestinationPlanner.candidates(4D, 12D, -2D, 4D, 12D, -2D, 6D).getFirst();

        assertEquals(4D, first.x(), 1.0E-9D);
        assertEquals(6D, first.z() - -2D, 1.0E-9D);
    }

    private static double horizontalDistanceSquared(
            double x,
            double z,
            double threatX,
            double threatZ) {
        double dx = x - threatX;
        double dz = z - threatZ;
        return dx * dx + dz * dz;
    }
}
