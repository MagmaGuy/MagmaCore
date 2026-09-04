package com.magmaguy.easyminecraftgoals.visuals.terrain;

import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTopSurfaceSamplerTest {
    private static final TerrainSurfaceEligibility.MaterialTraits AIR = traits(
            true, false, false, false, false, false, true);
    private static final TerrainSurfaceEligibility.MaterialTraits STONE = traits(
            false, false, true, true, false, false, false);
    private static final TerrainSurfaceEligibility.MaterialTraits WATER = traits(
            false, true, false, false, false, false, true);
    private static final TerrainSurfaceEligibility.MaterialTraits GLASS = traits(
            false, false, true, false, false, false, false);
    private static final TerrainSurfaceEligibility.MaterialTraits FLOWER = traits(
            false, false, false, false, false, false, true);

    @Test
    void omitsUnloadedColumnsWithoutReadingThem() {
        TerrainGrid terrain = new TerrainGrid().surface(0, 64, 0);

        List<LocalTopSurfaceSampler.SurfaceTile> sampled = LocalTopSurfaceSampler.sample(
                0.5, 65.0, 0.5, 1.5, 48, terrain);

        assertEquals(1, sampled.size());
        assertEquals(0, sampled.getFirst().x());
        assertEquals(64, sampled.getFirst().y());
        assertEquals(0, sampled.getFirst().z());
    }

    @Test
    void rejectsLiquidAndUnsupportedSolidObstructionsInsteadOfTunneling() {
        TerrainGrid water = new TerrainGrid()
                .surface(0, 64, 0)
                .block(0, 65, 0, WATER);
        TerrainGrid glass = new TerrainGrid()
                .surface(0, 64, 0)
                .block(0, 65, 0, GLASS);

        assertTrue(LocalTopSurfaceSampler.sample(
                0.5, 65.0, 0.5, 0.5, 48, water).isEmpty());
        assertTrue(LocalTopSurfaceSampler.sample(
                0.5, 65.0, 0.5, 0.5, 48, glass).isEmpty());
    }

    @Test
    void rejectsPassableDecorationInsteadOfUsingTheGroundBelowIt() {
        TerrainGrid terrain = new TerrainGrid()
                .surface(0, 64, 0)
                .block(0, 65, 0, FLOWER);

        assertTrue(LocalTopSurfaceSampler.sample(
                0.5, 65.0, 0.5, 0.5, 48, terrain).isEmpty());
    }

    @Test
    void excludesAHeightDiscontinuityFromTheCenterConnectedSurface() {
        TerrainGrid terrain = new TerrainGrid()
                .surface(0, 64, 0)
                .surface(1, 66, 0);

        List<LocalTopSurfaceSampler.SurfaceTile> sampled = LocalTopSurfaceSampler.sample(
                0.5, 65.0, 0.5, 2.0, 48, terrain);

        assertEquals(1, sampled.size());
        assertEquals(0, sampled.getFirst().x());
        assertEquals(64, sampled.getFirst().y());
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

    private static final class TerrainGrid implements LocalTopSurfaceSampler.BlockAccess {
        private static final BlockData BLOCK_DATA = blockData();
        private final Set<Column> loaded = new HashSet<>();
        private final Map<Position, TerrainSurfaceEligibility.MaterialTraits> blocks = new HashMap<>();

        TerrainGrid surface(int x, int y, int z) {
            return block(x, y, z, STONE);
        }

        TerrainGrid block(int x, int y, int z, TerrainSurfaceEligibility.MaterialTraits material) {
            loaded.add(new Column(x, z));
            blocks.put(new Position(x, y, z), material);
            return this;
        }

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
            return loaded.contains(new Column(x, z));
        }

        @Override
        public LocalTopSurfaceSampler.BlockSnapshot blockAt(int x, int y, int z) {
            if (!isColumnLoaded(x, z)) throw new AssertionError("Read unloaded column " + x + "," + z);
            TerrainSurfaceEligibility.MaterialTraits material = blocks.getOrDefault(
                    new Position(x, y, z), AIR);
            return new LocalTopSurfaceSampler.BlockSnapshot(BLOCK_DATA, material);
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

    private record Column(int x, int z) {}

    private record Position(int x, int y, int z) {}
}
