package com.magmaguy.easyminecraftgoals.visuals.terrain;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class LocalTopSurfaceSampler {
    private static final int SCAN_ABOVE_IMPACT = 2;
    private static final int SCAN_BELOW_IMPACT = 4;
    private static final int MAXIMUM_CONNECTED_STEP = 1;
    private static final int[][] CARDINAL_NEIGHBORS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private LocalTopSurfaceSampler() {
    }

    static List<SurfaceTile> sample(
            double centerX,
            double centerY,
            double centerZ,
            double radius,
            int maximumTiles,
            BlockAccess blocks) {
        Objects.requireNonNull(blocks, "blocks");
        if (!Double.isFinite(centerX) || !Double.isFinite(centerY) || !Double.isFinite(centerZ)
                || !Double.isFinite(radius) || radius <= 0.0 || maximumTiles < 1) return List.of();

        int minimumX = floor(centerX - radius - 0.5);
        int maximumX = floor(centerX + radius + 0.5);
        int minimumZ = floor(centerZ - radius - 0.5);
        int maximumZ = floor(centerZ + radius + 0.5);
        double radiusSquared = radius * radius;
        Map<Column, SurfaceTile> candidates = new HashMap<>();

        for (int x = minimumX; x <= maximumX; x++) {
            for (int z = minimumZ; z <= maximumZ; z++) {
                double deltaX = x + 0.5 - centerX;
                double deltaZ = z + 0.5 - centerZ;
                double radialDistanceSquared = deltaX * deltaX + deltaZ * deltaZ;
                if (radialDistanceSquared > radiusSquared) continue;
                double radialDistance = Math.sqrt(radialDistanceSquared);
                Optional<SurfaceTile> tile = scanColumn(
                        x, floor(centerY), z, radialDistance, radius, blocks);
                if (tile.isPresent()) candidates.put(new Column(x, z), tile.orElseThrow());
            }
        }
        if (candidates.isEmpty()) return List.of();

        SurfaceTile seed = candidates.values().stream()
                .min(SURFACE_ORDER)
                .orElseThrow();
        Column seedColumn = new Column(seed.x(), seed.z());
        Set<Column> connected = new LinkedHashSet<>();
        ArrayDeque<Column> pending = new ArrayDeque<>();
        connected.add(seedColumn);
        pending.add(seedColumn);

        while (!pending.isEmpty()) {
            Column currentColumn = pending.removeFirst();
            SurfaceTile current = candidates.get(currentColumn);
            for (int[] offset : CARDINAL_NEIGHBORS) {
                Column neighborColumn = new Column(
                        currentColumn.x() + offset[0],
                        currentColumn.z() + offset[1]);
                if (connected.contains(neighborColumn)) continue;
                SurfaceTile neighbor = candidates.get(neighborColumn);
                if (neighbor == null || Math.abs(neighbor.y() - current.y()) > MAXIMUM_CONNECTED_STEP) continue;
                connected.add(neighborColumn);
                pending.addLast(neighborColumn);
            }
        }

        List<SurfaceTile> ordered = new ArrayList<>(connected.size());
        for (Column column : connected) ordered.add(candidates.get(column));
        ordered.sort(SURFACE_ORDER);
        if (ordered.size() > maximumTiles) ordered = ordered.subList(0, maximumTiles);
        return List.copyOf(ordered);
    }

    private static Optional<SurfaceTile> scanColumn(
            int x,
            int impactY,
            int z,
            double radialDistance,
            double radius,
            BlockAccess blocks) {
        if (!blocks.isColumnLoaded(x, z)) return Optional.empty();
        int top = Math.min(blocks.maximumHeight() - 1, impactY + SCAN_ABOVE_IMPACT);
        int bottom = Math.max(blocks.minimumHeight(), impactY - SCAN_BELOW_IMPACT);
        if (top < bottom) return Optional.empty();

        for (int y = top; y >= bottom; y--) {
            BlockSnapshot candidate = blocks.blockAt(x, y, z);
            TerrainSurfaceEligibility.MaterialTraits material = candidate.material();
            if (material.air()) continue;
            if (material.liquid()) return Optional.empty();
            if (!TerrainSurfaceEligibility.isEligibleSurface(material)) return Optional.empty();
            if (y + 1 >= blocks.maximumHeight()) return Optional.empty();

            BlockSnapshot clearance = blocks.blockAt(x, y + 1, z);
            if (!TerrainSurfaceEligibility.hasClearance(clearance.material())) return Optional.empty();
            double falloff = Math.max(0.0, 1.0 - radialDistance / radius);
            return Optional.of(new SurfaceTile(
                    x, y, z, candidate.blockData(), radialDistance, falloff));
        }
        return Optional.empty();
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static final Comparator<SurfaceTile> SURFACE_ORDER = Comparator
            .comparingDouble(SurfaceTile::radialDistance)
            .thenComparingInt(SurfaceTile::x)
            .thenComparingInt(SurfaceTile::z)
            .thenComparingInt(SurfaceTile::y);

    interface BlockAccess {
        int minimumHeight();

        int maximumHeight();

        boolean isColumnLoaded(int x, int z);

        BlockSnapshot blockAt(int x, int y, int z);
    }

    static final class BukkitBlockAccess implements BlockAccess {
        private final World world;

        BukkitBlockAccess(World world) {
            this.world = Objects.requireNonNull(world, "world");
        }

        @Override
        public int minimumHeight() {
            return world.getMinHeight();
        }

        @Override
        public int maximumHeight() {
            return world.getMaxHeight();
        }

        @Override
        public boolean isColumnLoaded(int x, int z) {
            return world.isChunkLoaded(x >> 4, z >> 4);
        }

        @Override
        public BlockSnapshot blockAt(int x, int y, int z) {
            Block block = world.getBlockAt(x, y, z);
            Material material = block.getType();
            boolean air = material.isAir();
            boolean liquid = block.isLiquid();
            boolean solid = material.isSolid();
            boolean occluding = material.isOccluding();
            boolean interactable = material.isInteractable();
            boolean tileState = !air && !liquid && block.getState() instanceof TileState;
            TerrainSurfaceEligibility.MaterialTraits traits = new TerrainSurfaceEligibility.MaterialTraits(
                    air,
                    liquid,
                    solid,
                    occluding,
                    interactable,
                    tileState,
                    block.isPassable());
            return new BlockSnapshot(block.getBlockData(), traits);
        }
    }

    record BlockSnapshot(
            BlockData blockData,
            TerrainSurfaceEligibility.MaterialTraits material) {
        BlockSnapshot {
            blockData = Objects.requireNonNull(blockData, "blockData").clone();
            material = Objects.requireNonNull(material, "material");
        }

        @Override
        public BlockData blockData() {
            return blockData.clone();
        }
    }

    record SurfaceTile(
            int x,
            int y,
            int z,
            BlockData blockData,
            double radialDistance,
            double radialFalloff) {
        SurfaceTile {
            blockData = Objects.requireNonNull(blockData, "blockData").clone();
        }

        @Override
        public BlockData blockData() {
            return blockData.clone();
        }
    }

    private record Column(int x, int z) {}
}
