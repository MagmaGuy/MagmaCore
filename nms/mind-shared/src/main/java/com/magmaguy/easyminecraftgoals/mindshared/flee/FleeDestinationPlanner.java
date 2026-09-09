package com.magmaguy.easyminecraftgoals.mindshared.flee;

import java.util.ArrayList;
import java.util.List;

/** Builds deterministic ground destinations which all lead away from one threat point. */
final class FleeDestinationPlanner {
    private static final double[] OFFSETS_DEGREES = {0D, 25D, -25D, 50D, -50D, 75D, -75D};

    private FleeDestinationPlanner() {
    }

    static List<Destination> candidates(
            double bodyX,
            double bodyY,
            double bodyZ,
            double threatX,
            double threatY,
            double threatZ,
            double distance) {
        if (!Double.isFinite(distance) || distance <= 0D) {
            throw new IllegalArgumentException("distance must be finite and positive");
        }
        double awayX = bodyX - threatX;
        double awayZ = bodyZ - threatZ;
        double magnitude = Math.hypot(awayX, awayZ);
        if (magnitude < 1.0E-6D) {
            awayX = 0D;
            awayZ = 1D;
        } else {
            awayX /= magnitude;
            awayZ /= magnitude;
        }

        List<Destination> candidates = new ArrayList<>(OFFSETS_DEGREES.length);
        for (double offsetDegrees : OFFSETS_DEGREES) {
            double radians = Math.toRadians(offsetDegrees);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double directionX = awayX * cos - awayZ * sin;
            double directionZ = awayX * sin + awayZ * cos;
            candidates.add(new Destination(
                    bodyX + directionX * distance,
                    bodyY,
                    bodyZ + directionZ * distance));
        }
        return List.copyOf(candidates);
    }

    record Destination(double x, double y, double z) {
    }
}
