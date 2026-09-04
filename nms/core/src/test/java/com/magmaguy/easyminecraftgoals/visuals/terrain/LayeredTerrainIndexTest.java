package com.magmaguy.easyminecraftgoals.visuals.terrain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayeredTerrainIndexTest {

    @Test
    void removingTheNewestImpactRevealsTheStillActiveTerrainBelowIt() {
        LayeredTerrainIndex<String, String> index = new LayeredTerrainIndex<>();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        index.replace(first, Map.of("0,64,0", "first"));
        index.replace(second, Map.of("0,64,0", "second"));

        assertEquals(Optional.of("second"), index.visible("0,64,0"));
        assertEquals(List.of(new LayeredTerrainIndex.VisibilityChange<>(
                        "0,64,0", Optional.of("second"), Optional.of("first"))),
                index.remove(second));
    }

    @Test
    void changingAnObscuredImpactDoesNotCreateAFalseVisibleTransition() {
        LayeredTerrainIndex<String, String> index = new LayeredTerrainIndex<>();
        UUID lower = UUID.randomUUID();
        UUID upper = UUID.randomUUID();

        index.replace(lower, Map.of("0,64,0", "lower"));
        index.replace(upper, Map.of("0,64,0", "upper"));

        assertTrue(index.replace(lower, Map.of("0,64,0", "changed")).isEmpty());
        assertEquals(Optional.of("upper"), index.visible("0,64,0"));
    }

    @Test
    void replacingAnImpactReportsRemovedAndAddedCoordinatesAtomically() {
        LayeredTerrainIndex<String, String> index = new LayeredTerrainIndex<>();
        UUID impact = UUID.randomUUID();

        index.replace(impact, Map.of("0,64,0", "old"));

        assertEquals(List.of(
                        new LayeredTerrainIndex.VisibilityChange<>(
                                "0,64,0", Optional.of("old"), Optional.empty()),
                        new LayeredTerrainIndex.VisibilityChange<>(
                                "1,64,0", Optional.empty(), Optional.of("new"))),
                index.replace(impact, Map.of("1,64,0", "new")));
    }
}
