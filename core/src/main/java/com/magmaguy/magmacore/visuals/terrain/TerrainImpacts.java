package com.magmaguy.magmacore.visuals.terrain;

import org.bukkit.Location;

/**
 * Public terrain-impact facade used by gameplay code and scripting adapters.
 */
public final class TerrainImpacts {

    private TerrainImpacts() {}

    public static TerrainImpactHandle crack(Location center) {
        if (center == null) return TerrainImpactHandle.inactive();
        return show(new TerrainImpactRequest(center));
    }

    public static TerrainImpactHandle show(TerrainImpactRequest request) {
        return TerrainImpactService.show(request);
    }
}
