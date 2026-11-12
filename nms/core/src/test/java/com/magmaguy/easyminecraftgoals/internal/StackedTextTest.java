package com.magmaguy.easyminecraftgoals.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which lines a viewer of a stacked text gets: everyone's, or their own. */
class StackedTextTest {
    private final StackedText text = new StackedText();
    private final UUID rider = UUID.randomUUID(), other = UUID.randomUUID();

    @Test
    void everyViewerGetsTheSharedLines() {
        text.setLines(List.of("Pingu", "10 / 20", "||||"));
        assertEquals(List.of("Pingu", "10 / 20", "||||"), text.linesFor(rider));
        assertEquals(List.of("Pingu", "10 / 20", "||||"), text.linesFor(other));
    }

    @Test
    void aViewerGivenTheirOwnLinesGetsThoseAndNobodyElseDoes() {
        text.setLines(List.of("Pingu", "10 / 20", "||||"));
        text.setLinesFor(rider, List.of("Pingu"));
        assertEquals(List.of("Pingu"), text.linesFor(rider));
        assertEquals(List.of("Pingu", "10 / 20", "||||"), text.linesFor(other));
    }

    @Test
    void aViewerGivenNoLinesGetsNone() {
        text.setLines(List.of("10 / 20", "||||"));
        text.setLinesFor(rider, List.of());
        assertEquals(List.of(), text.linesFor(rider));
        assertEquals(List.of("10 / 20", "||||"), text.linesFor(other));
    }

    @Test
    void aViewersOwnLinesOutlastChangesToTheSharedOnes() {
        text.setLinesFor(rider, List.of("Pingu"));
        text.setLines(List.of("Pingu", "9 / 20", "|||"));
        assertEquals(List.of("Pingu"), text.linesFor(rider));
        assertEquals(List.of("Pingu", "9 / 20", "|||"), text.linesFor(other));
    }

    @Test
    void nullGivesAViewerTheSharedLinesAgain() {
        text.setLines(List.of("Pingu", "10 / 20"));
        text.setLinesFor(rider, List.of("Pingu"));
        text.setLinesFor(rider, null);
        assertEquals(List.of("Pingu", "10 / 20"), text.linesFor(rider));
        text.setLines(List.of("Pingu"));
        assertEquals(List.of("Pingu"), text.linesFor(rider));
    }

    @Test
    void aViewersOwnLinesAreSplitIntoRowsAsTheSharedOnesAre() {
        text.setLines(List.of("§aPingu\n10 / 20"));
        text.setLinesFor(rider, List.of("§aPingu\nthe brave"));
        assertEquals(List.of("§aPingu", "§a10 / 20"), text.linesFor(other));
        assertEquals(List.of("§aPingu", "§athe brave"), text.linesFor(rider));
    }
}
