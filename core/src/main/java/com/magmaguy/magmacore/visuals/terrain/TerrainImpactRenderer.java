package com.magmaguy.magmacore.visuals.terrain;

/**
 * Provider seam implemented by the active packet renderer.
 */
public interface TerrainImpactRenderer extends AutoCloseable {

    TerrainImpactHandle show(TerrainImpactRequest request);

    @Override
    default void close() {}
}
