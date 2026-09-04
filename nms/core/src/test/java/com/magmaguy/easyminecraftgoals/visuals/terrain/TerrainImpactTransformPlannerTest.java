package com.magmaguy.easyminecraftgoals.visuals.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainImpactTransformPlannerTest {
    @Test
    void producesAStableSubtleTransformAroundTheBlockCenter() {
        TerrainImpactPlan.Transform first = TerrainImpactTransformPlanner.plan(
                0x4D41474D41434F52L, 3, 64, -2, 0.75, 1.0);
        TerrainImpactPlan.Transform repeated = TerrainImpactTransformPlanner.plan(
                0x4D41474D41434F52L, 3, 64, -2, 0.75, 1.0);

        assertEquals(first, repeated);
        assertTrue(first.uniformScale() >= 1.012 && first.uniformScale() <= 1.04);
        assertTrue(Math.abs(first.tiltXDegrees()) <= 4.0);
        assertTrue(Math.abs(first.tiltZDegrees()) <= 4.0);
        assertTrue(first.lift() > 0.0 && first.lift() <= 0.04);

        TerrainImpactPlan.Point transformedCenter = first.applyTo(0.5, 0.5, 0.5);
        assertEquals(0.5, transformedCenter.x(), 1.0E-9);
        assertEquals(0.5 + first.lift(), transformedCenter.y(), 1.0E-9);
        assertEquals(0.5, transformedCenter.z(), 1.0E-9);
    }
}
