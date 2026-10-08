package com.magmaguy.magmacore.rails;

import org.bukkit.block.data.Rail;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RailTrackTest {
    private final Map<String, Rail.Shape> rails = new HashMap<>();

    private void rail(int x, int y, int z, Rail.Shape shape) {
        rails.put(x + "," + y + "," + z, shape);
    }

    private RailTrack start(double x, double y, double z, double lookX, double lookZ) {
        return RailTrack.start((rx, ry, rz) -> rails.get(rx + "," + ry + "," + rz), x, y, z, lookX, lookZ);
    }

    @Test
    void noRailUnderThePlayerStartsNothing() {
        rail(0, 64, 1, Rail.Shape.NORTH_SOUTH);
        assertNull(start(0.5, 64, 0.5, 0, 1));
    }

    @Test
    void headsTheWayThePlayerLooksAndStopsAtTheEnd() {
        for (int z = -3; z <= 3; z++) rail(0, 64, z, Rail.Shape.NORTH_SOUTH);
        RailTrack north = start(0.5, 64, 0.5, 0.2, -1);
        assertTrue(north.advance(2));
        assertEquals(-1.5, north.z(), 1e-9);
        assertFalse(north.advance(10), "the track ends three cells north");
        assertEquals(-3, north.z(), 1e-9, "the cart stops on the last rail's far edge");

        RailTrack south = start(0.5, 64, 0.5, 0, 1);
        assertTrue(south.advance(2));
        assertEquals(2.5, south.z(), 1e-9);
    }

    @Test
    void followsCurvesAndSlopesUpAndDown() {
        rail(0, 64, 0, Rail.Shape.EAST_WEST);
        rail(1, 64, 0, Rail.Shape.ASCENDING_EAST);
        rail(2, 65, 0, Rail.Shape.SOUTH_WEST);
        rail(2, 64, 1, Rail.Shape.ASCENDING_NORTH);
        rail(2, 64, 2, Rail.Shape.NORTH_SOUTH);
        RailTrack track = start(0.5, 64, 0.5, 1, 0);
        // Half of the flat cell, the slope's diagonal, half of the curve's diagonal.
        assertTrue(track.advance(0.5 + Math.sqrt(2) + Math.sqrt(0.5) / 2));
        assertEquals(2.25, track.x(), 1e-9, "halfway round the curve");
        assertEquals(65, track.y(), 1e-9, "level with the top of the slope");
        assertEquals(0.75, track.z(), 1e-9);
        // The rest of the curve, then down the slope that rises back toward the curve.
        assertTrue(track.advance(Math.sqrt(0.5) / 2 + Math.sqrt(2) + 0.5));
        assertEquals(2.5, track.x(), 1e-9);
        assertEquals(64, track.y(), 1e-9, "back down on the flat rail");
        assertEquals(2.5, track.z(), 1e-9);
    }

    @Test
    void doesNotJoinRailsThatDoNotConnect() {
        rail(0, 64, 0, Rail.Shape.NORTH_SOUTH);
        rail(0, 64, -1, Rail.Shape.EAST_WEST);
        RailTrack track = start(0.5, 64, 0.5, 0, -1);
        assertFalse(track.advance(1));
        assertEquals(0, track.z(), 1e-9);
    }
}
