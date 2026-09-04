package com.magmaguy.easyminecraftgoals.visuals.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainSurfaceEligibilityTest {
    @Test
    void acceptsOnlySafeOpaqueTerrainWithPassableClearance() {
        TerrainSurfaceEligibility.MaterialTraits stone = traits(false, false, true, true, false, false, false);
        TerrainSurfaceEligibility.MaterialTraits air = traits(true, false, false, false, false, false, true);
        TerrainSurfaceEligibility.MaterialTraits water = traits(false, true, false, false, false, false, true);
        TerrainSurfaceEligibility.MaterialTraits glass = traits(false, false, true, false, false, false, false);
        TerrainSurfaceEligibility.MaterialTraits craftingTable = traits(false, false, true, true, true, false, false);
        TerrainSurfaceEligibility.MaterialTraits passableControl = traits(false, false, false, false, true, false, true);
        TerrainSurfaceEligibility.MaterialTraits tileState = traits(false, false, true, true, false, true, false);
        TerrainSurfaceEligibility.MaterialTraits shortGrass = traits(false, false, false, false, false, false, true);

        assertTrue(TerrainSurfaceEligibility.isEligibleSurface(stone));
        assertFalse(TerrainSurfaceEligibility.isEligibleSurface(air));
        assertFalse(TerrainSurfaceEligibility.isEligibleSurface(water));
        assertFalse(TerrainSurfaceEligibility.isEligibleSurface(glass));
        assertFalse(TerrainSurfaceEligibility.isEligibleSurface(craftingTable));
        assertFalse(TerrainSurfaceEligibility.isEligibleSurface(tileState));

        assertTrue(TerrainSurfaceEligibility.hasClearance(air));
        assertFalse(TerrainSurfaceEligibility.hasClearance(shortGrass));
        assertFalse(TerrainSurfaceEligibility.hasClearance(water));
        assertFalse(TerrainSurfaceEligibility.hasClearance(passableControl));
        assertFalse(TerrainSurfaceEligibility.hasClearance(stone));
    }

    private static TerrainSurfaceEligibility.MaterialTraits traits(
            boolean air,
            boolean liquid,
            boolean solid,
            boolean occluding,
            boolean interactable,
            boolean tileState,
            boolean passable) {
        return new TerrainSurfaceEligibility.MaterialTraits(
                air, liquid, solid, occluding, interactable, tileState, passable);
    }
}
