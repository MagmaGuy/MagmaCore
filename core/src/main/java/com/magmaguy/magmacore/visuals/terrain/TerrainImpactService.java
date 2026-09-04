package com.magmaguy.magmacore.visuals.terrain;

import java.util.Objects;

/**
 * Owns the renderer installed by the active NMS adapter.
 */
public final class TerrainImpactService {
    private static final Object LOCK = new Object();
    private static volatile TerrainImpactRenderer renderer;

    private TerrainImpactService() {}

    public static void install(TerrainImpactRenderer replacement) {
        Objects.requireNonNull(replacement, "replacement");
        TerrainImpactRenderer previous;
        synchronized (LOCK) {
            previous = renderer;
            renderer = replacement;
        }
        if (previous != null && previous != replacement) close(previous);
    }

    public static void shutdown() {
        TerrainImpactRenderer previous;
        synchronized (LOCK) {
            previous = renderer;
            renderer = null;
        }
        if (previous != null) close(previous);
    }

    static TerrainImpactHandle show(TerrainImpactRequest request) {
        if (request == null) return TerrainImpactHandle.inactive();
        TerrainImpactRenderer current = renderer;
        if (current == null) return TerrainImpactHandle.inactive();
        try {
            TerrainImpactHandle handle = current.show(request);
            return handle == null ? TerrainImpactHandle.inactive() : handle;
        } catch (RuntimeException ignored) {
            return TerrainImpactHandle.inactive();
        }
    }

    private static void close(TerrainImpactRenderer renderer) {
        try {
            renderer.close();
        } catch (RuntimeException ignored) {
            // Shutdown remains best-effort even if a provider has already torn itself down.
        }
    }
}
