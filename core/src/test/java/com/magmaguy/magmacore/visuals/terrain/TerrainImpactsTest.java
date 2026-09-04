package com.magmaguy.magmacore.visuals.terrain;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TerrainImpactsTest {

    @BeforeEach
    @AfterEach
    void resetRenderer() {
        TerrainImpactService.shutdown();
    }

    @Test
    void delegatesToTheInstalledRendererAndReturnsToInactiveOnShutdown() {
        Location center = new Location(mock(World.class), 12.0, 70.0, -3.0);
        TerrainImpactHandle unavailable = TerrainImpacts.crack(center);
        RecordingRenderer renderer = new RecordingRenderer();

        TerrainImpactService.install(renderer);
        TerrainImpactHandle rendered = TerrainImpacts.crack(center);

        assertFalse(unavailable.active());
        unavailable.close();
        assertEquals(new UUID(0L, 0L), unavailable.id());
        assertSame(renderer.handle, rendered);
        assertEquals(4.5, renderer.request.radius());
        assertEquals(35, renderer.request.durationTicks());

        TerrainImpactService.shutdown();

        assertTrue(renderer.closed);
        assertFalse(TerrainImpacts.show(new TerrainImpactRequest(center)).active());
    }

    private static final class RecordingRenderer implements TerrainImpactRenderer {
        private final RecordingHandle handle = new RecordingHandle();
        private TerrainImpactRequest request;
        private boolean closed;

        @Override
        public TerrainImpactHandle show(TerrainImpactRequest request) {
            this.request = request;
            return handle;
        }

        @Override
        public void close() {
            closed = true;
            handle.close();
        }
    }

    private static final class RecordingHandle implements TerrainImpactHandle {
        private final UUID id = UUID.fromString("1420f76d-2bf4-4d85-bcab-23dc744aadee");
        private boolean active = true;

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public void close() {
            active = false;
        }
    }
}
