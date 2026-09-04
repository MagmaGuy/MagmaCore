package com.magmaguy.easyminecraftgoals.internal.pathfinding;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotPathSolverTest {
    private static final SnapshotPathSolver.BlockProperties AIR =
            new SnapshotPathSolver.BlockProperties(true, false, false, false, false);
    private static final SnapshotPathSolver.BlockProperties SOLID =
            new SnapshotPathSolver.BlockProperties(false, true, false, false, false);
    private static final SnapshotPathSolver.BodyProfile GROUND_BODY = new SnapshotPathSolver.BodyProfile(
            SnapshotPathSolver.MovementMode.GROUND,
            0,
            2);

    @Test
    void findsLongGroundPathWithoutPreauthoredLegs() {
        FakeTerrain terrain = new FakeTerrain(-4, 40, -8, 8);

        SnapshotPathSolver.Result result = SnapshotPathSolver.solve(
                terrain,
                new SnapshotPathSolver.Point(0, 1, 0),
                new SnapshotPathSolver.Point(32, 1, 0),
                GROUND_BODY,
                20_000);

        assertTrue(result.found());
        assertTrue(result.points().size() >= 33);
    }

    @Test
    void routesAroundSolidCityWallThroughOpening() {
        FakeTerrain terrain = new FakeTerrain(-4, 20, -8, 8);
        for (int z = -6; z <= 6; z++) {
            if (z == 4) continue;
            terrain.column(8, z, 1, 3, SOLID);
        }

        SnapshotPathSolver.Result result = SnapshotPathSolver.solve(
                terrain,
                new SnapshotPathSolver.Point(0, 1, 0),
                new SnapshotPathSolver.Point(16, 1, 0),
                GROUND_BODY,
                30_000);

        assertTrue(result.found());
        assertTrue(result.points().stream().anyMatch(point -> point.x() == 8 && point.z() == 4));
    }

    @Test
    void reportsNoPathWhenKnownTerrainIsSealed() {
        FakeTerrain terrain = new FakeTerrain(-2, 12, -3, 3);
        for (int z = -3; z <= 3; z++) terrain.column(5, z, 1, 5, SOLID);

        SnapshotPathSolver.Result result = SnapshotPathSolver.solve(
                terrain,
                new SnapshotPathSolver.Point(0, 1, 0),
                new SnapshotPathSolver.Point(10, 1, 0),
                GROUND_BODY,
                20_000);

        assertFalse(result.found());
        assertFalse(result.exhaustedNodeBudget());
    }

    private static final class FakeTerrain implements SnapshotPathSolver.Terrain {
        private final int minimumX;
        private final int maximumX;
        private final int minimumZ;
        private final int maximumZ;
        private final Map<SnapshotPathSolver.Point, SnapshotPathSolver.BlockProperties> blocks = new HashMap<>();

        private FakeTerrain(int minimumX, int maximumX, int minimumZ, int maximumZ) {
            this.minimumX = minimumX;
            this.maximumX = maximumX;
            this.minimumZ = minimumZ;
            this.maximumZ = maximumZ;
        }

        private void column(
                int x,
                int z,
                int minimumY,
                int maximumY,
                SnapshotPathSolver.BlockProperties properties) {
            for (int y = minimumY; y <= maximumY; y++) {
                blocks.put(new SnapshotPathSolver.Point(x, y, z), properties);
            }
        }

        @Override
        public SnapshotPathSolver.BlockProperties blockProperties(int x, int y, int z) {
            if (x < minimumX || x > maximumX || z < minimumZ || z > maximumZ) {
                return SnapshotPathSolver.BlockProperties.UNKNOWN;
            }
            SnapshotPathSolver.BlockProperties explicit = blocks.get(new SnapshotPathSolver.Point(x, y, z));
            if (explicit != null) return explicit;
            return y <= 0 ? SOLID : AIR;
        }

        @Override
        public int minimumY() {
            return -16;
        }

        @Override
        public int maximumY() {
            return 32;
        }
    }
}
