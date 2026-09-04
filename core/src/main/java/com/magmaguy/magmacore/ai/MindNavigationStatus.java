package com.magmaguy.magmacore.ai;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable navigation diagnostics sampled at the current native Mind tick.
 *
 * <p>An active destination survives a failed native path request so a behavior can detect that it
 * is stuck. It resets when movement is explicitly stopped, the owning MOVE lease is released, the
 * program retires, or the body reaches the destination. Reissuing move requests does not reset
 * progress history. Consecutive stuck ticks reset only after observed body movement or a full
 * destination reset.</p>
 */
public record MindNavigationStatus(
        Optional<MindPosition> destination,
        boolean navigationInProgress,
        Optional<MindNavigationProgress> lastProgress,
        int consecutiveStuckTicks) {

    private static final MindNavigationStatus IDLE =
            new MindNavigationStatus(Optional.empty(), false, Optional.empty(), 0);

    public MindNavigationStatus {
        destination = Objects.requireNonNull(destination, "destination");
        lastProgress = Objects.requireNonNull(lastProgress, "lastProgress");
        if (consecutiveStuckTicks < 0) {
            throw new IllegalArgumentException("consecutiveStuckTicks cannot be negative");
        }
        if (destination.isEmpty()
                && (navigationInProgress || lastProgress.isPresent() || consecutiveStuckTicks != 0)) {
            throw new IllegalArgumentException("Idle navigation cannot retain progress or stuck state");
        }
    }

    public static MindNavigationStatus idle() {
        return IDLE;
    }

    public boolean destinationActive() {
        return destination.isPresent();
    }
}
