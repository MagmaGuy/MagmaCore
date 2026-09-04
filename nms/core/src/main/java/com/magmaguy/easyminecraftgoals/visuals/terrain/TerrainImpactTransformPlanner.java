package com.magmaguy.easyminecraftgoals.visuals.terrain;

final class TerrainImpactTransformPlanner {
    private static final double MINIMUM_BASELINE_SCALE_DELTA = 0.012;
    private static final double VARIABLE_BASELINE_SCALE_DELTA = 0.028;
    private static final double MAXIMUM_TILT_DEGREES = 4.0;
    private static final double MINIMUM_BASELINE_LIFT = 0.006;
    private static final double VARIABLE_BASELINE_LIFT = 0.018;

    private TerrainImpactTransformPlanner() {
    }

    static TerrainImpactPlan.Transform plan(
            long seed,
            int x,
            int y,
            int z,
            double radialFalloff,
            double intensity) {
        double boundedFalloff = clamp(radialFalloff, 0.0, 1.0);
        double boundedIntensity = clamp(intensity, 0.0, 1.0);
        double radialInfluence = 0.35 + 0.65 * boundedFalloff;

        long coordinateSeed = mix(seed
                ^ (long) x * 0x632BE59BD9B4E019L
                ^ (long) y * 0x9E3779B185EBCA87L
                ^ (long) z * 0xC2B2AE3D27D4EB4FL);
        double scaleNoise = unit(mix(coordinateSeed ^ 0x243F6A8885A308D3L));
        double tiltXNoise = signedUnit(mix(coordinateSeed ^ 0x13198A2E03707344L));
        double tiltZNoise = signedUnit(mix(coordinateSeed ^ 0xA4093822299F31D0L));
        double liftNoise = unit(mix(coordinateSeed ^ 0x082EFA98EC4E6C89L));

        double scale = 1.0 + boundedIntensity * (
                MINIMUM_BASELINE_SCALE_DELTA
                        + VARIABLE_BASELINE_SCALE_DELTA * radialInfluence * scaleNoise);
        double maximumTilt = MAXIMUM_TILT_DEGREES * radialInfluence * boundedIntensity;
        double tiltX = tiltXNoise * maximumTilt;
        double tiltZ = tiltZNoise * maximumTilt;
        double lift = boundedIntensity * (
                MINIMUM_BASELINE_LIFT + VARIABLE_BASELINE_LIFT * radialInfluence * liftNoise);

        TerrainImpactPlan.Transform uncentered = new TerrainImpactPlan.Transform(
                scale, tiltX, tiltZ, lift, 0.0, 0.0, 0.0);
        TerrainImpactPlan.Point transformedPivot = uncentered.applyTo(0.5, 0.5, 0.5);
        return new TerrainImpactPlan.Transform(
                scale,
                tiltX,
                tiltZ,
                lift,
                0.5 - transformedPivot.x(),
                0.5 + lift - transformedPivot.y(),
                0.5 - transformedPivot.z());
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private static double unit(long value) {
        return (value >>> 11) * 0x1.0p-53;
    }

    private static double signedUnit(long value) {
        return unit(value) * 2.0 - 1.0;
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }
}
