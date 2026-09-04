package com.magmaguy.magmacore.visuals.terrain;

import java.util.UUID;

/**
 * A removable terrain-impact visual. Closing a handle more than once is safe.
 */
public interface TerrainImpactHandle extends AutoCloseable {

    UUID id();

    boolean active();

    @Override
    void close();

    static TerrainImpactHandle inactive() {
        return InactiveTerrainImpactHandle.INSTANCE;
    }
}

final class InactiveTerrainImpactHandle implements TerrainImpactHandle {
    static final InactiveTerrainImpactHandle INSTANCE = new InactiveTerrainImpactHandle();
    private static final UUID ID = new UUID(0L, 0L);

    private InactiveTerrainImpactHandle() {}

    @Override
    public UUID id() {
        return ID;
    }

    @Override
    public boolean active() {
        return false;
    }

    @Override
    public void close() {
        // Deliberately inert.
    }
}
