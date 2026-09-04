package com.magmaguy.easyminecraftgoals.visuals.terrain;

import com.magmaguy.magmacore.visuals.terrain.TerrainImpactRequest;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainImpactPlannerTest {
    @Test
    void capsTheCenterConnectedDiscInRadialOrder() {
        TerrainImpactRequest request = new TerrainImpactRequest(
                new Location(null, 0.5, 64.0, 0.5), 6.0, 1.0, 35, 48.0, 7321L);
        LocalTopSurfaceSampler.BlockAccess flatTerrain = new FlatTerrainAccess();

        TerrainImpactPlan first = TerrainImpactPlanner.plan(request, flatTerrain);
        TerrainImpactPlan repeated = TerrainImpactPlanner.plan(request, flatTerrain);

        assertEquals(48, first.tiles().size());
        assertEquals(first, repeated);
        assertEquals(0.0, first.tiles().getFirst().radialDistance(), 1.0E-9);
        assertEquals(1.0, first.tiles().getFirst().radialFalloff(), 1.0E-9);
        for (int index = 1; index < first.tiles().size(); index++) {
            TerrainImpactPlan.Tile previous = first.tiles().get(index - 1);
            TerrainImpactPlan.Tile current = first.tiles().get(index);
            assertTrue(previous.radialDistance() <= current.radialDistance());
            assertTrue(previous.radialFalloff() >= current.radialFalloff());
        }
    }

    private static final class FlatTerrainAccess implements LocalTopSurfaceSampler.BlockAccess {
        private static final BlockData BLOCK_DATA = blockData();
        private static final TerrainSurfaceEligibility.MaterialTraits AIR = new TerrainSurfaceEligibility.MaterialTraits(
                true, false, false, false, false, false, true);
        private static final TerrainSurfaceEligibility.MaterialTraits STONE = new TerrainSurfaceEligibility.MaterialTraits(
                false, false, true, true, false, false, false);

        @Override
        public int minimumHeight() {
            return -64;
        }

        @Override
        public int maximumHeight() {
            return 320;
        }

        @Override
        public boolean isColumnLoaded(int x, int z) {
            return true;
        }

        @Override
        public LocalTopSurfaceSampler.BlockSnapshot blockAt(int x, int y, int z) {
            return y == 64
                    ? new LocalTopSurfaceSampler.BlockSnapshot(BLOCK_DATA, STONE)
                    : new LocalTopSurfaceSampler.BlockSnapshot(BLOCK_DATA, AIR);
        }
    }

    private static BlockData blockData() {
        return (BlockData) Proxy.newProxyInstance(
                BlockData.class.getClassLoader(),
                new Class<?>[]{BlockData.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "clone" -> proxy;
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> 31;
                    case "toString", "getAsString" -> "minecraft:stone";
                    default -> method.getReturnType().isPrimitive() ? primitiveDefault(method.getReturnType()) : null;
                });
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        throw new IllegalArgumentException("Unsupported primitive " + type);
    }
}
