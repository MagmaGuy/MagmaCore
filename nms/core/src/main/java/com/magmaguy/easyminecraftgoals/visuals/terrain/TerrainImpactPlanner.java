package com.magmaguy.easyminecraftgoals.visuals.terrain;

import com.magmaguy.magmacore.visuals.terrain.TerrainImpactRequest;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class TerrainImpactPlanner {
    static final int MAXIMUM_TILES = 48;

    TerrainImpactPlan plan(TerrainImpactRequest request) {
        Objects.requireNonNull(request, "request");
        Location center = request.center();
        World world = center.getWorld();
        if (world == null) return new TerrainImpactPlan(List.of());
        return plan(request, new LocalTopSurfaceSampler.BukkitBlockAccess(world));
    }

    static TerrainImpactPlan plan(
            TerrainImpactRequest request,
            LocalTopSurfaceSampler.BlockAccess blocks) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(blocks, "blocks");
        Location center = request.center();
        List<LocalTopSurfaceSampler.SurfaceTile> surfaces = LocalTopSurfaceSampler.sample(
                center.getX(),
                center.getY(),
                center.getZ(),
                request.radius(),
                MAXIMUM_TILES,
                blocks);
        if (surfaces.isEmpty()) return new TerrainImpactPlan(List.of());

        List<TerrainImpactPlan.Tile> tiles = new ArrayList<>(surfaces.size());
        for (LocalTopSurfaceSampler.SurfaceTile surface : surfaces) {
            TerrainImpactPlan.Transform transform = TerrainImpactTransformPlanner.plan(
                    request.seed(),
                    surface.x(),
                    surface.y(),
                    surface.z(),
                    surface.radialFalloff(),
                    request.intensity());
            tiles.add(new TerrainImpactPlan.Tile(
                    surface.x(),
                    surface.y(),
                    surface.z(),
                    surface.blockData(),
                    surface.radialDistance(),
                    surface.radialFalloff(),
                    transform));
        }
        return new TerrainImpactPlan(tiles);
    }
}
