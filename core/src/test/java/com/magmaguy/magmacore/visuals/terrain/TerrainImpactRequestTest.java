package com.magmaguy.magmacore.visuals.terrain;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TerrainImpactRequestTest {

    @Test
    void appliesSafeBoundsAndDefensivelyCopiesTheCenter() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.fromString("3c877e83-0b52-4981-92ef-15bdc0250dc3"));
        Location source = new Location(world, 10.25, 64.0, -7.5);

        TerrainImpactRequest bounded = new TerrainImpactRequest(
                source, Double.MAX_VALUE, -4.0, Integer.MAX_VALUE, Double.NaN, 91L);
        TerrainImpactRequest preset = new TerrainImpactRequest(source);

        source.setX(999.0);
        Location returnedCenter = bounded.center();
        returnedCenter.setZ(999.0);

        assertEquals(10.25, bounded.center().getX());
        assertEquals(-7.5, bounded.center().getZ());
        assertEquals(6.0, bounded.radius());
        assertEquals(0.0, bounded.intensity());
        assertEquals(200, bounded.durationTicks());
        assertEquals(48.0, bounded.viewRange());
        assertEquals(91L, bounded.seed());

        assertEquals(4.5, preset.radius());
        assertEquals(1.0, preset.intensity());
        assertEquals(35, preset.durationTicks());
        assertEquals(48.0, preset.viewRange());
    }
}
