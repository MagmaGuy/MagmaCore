package com.magmaguy.magmacore.ai;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MindNavigationStatusContractTest {
    @Test
    void activeStatusCarriesDestinationProgressAndStuckTicksTogether() {
        MindPosition position = new MindPosition("world", 1.0D, 2.0D, 3.0D);
        MindNavigationStatus status = new MindNavigationStatus(
                Optional.of(position),
                true,
                Optional.of(new MindNavigationProgress(42L, position)),
                3);

        assertTrue(status.destinationActive());
        assertTrue(status.navigationInProgress());
    }

    @Test
    void idleStatusCannotRetainStaleProgress() {
        assertFalse(MindNavigationStatus.idle().destinationActive());
        assertThrows(IllegalArgumentException.class, () -> new MindNavigationStatus(
                Optional.empty(),
                false,
                Optional.empty(),
                1));
    }
}
