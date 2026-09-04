package com.magmaguy.magmacore.ai;

import java.util.Objects;
import java.util.UUID;

public record MindFailure(
        UUID entityId,
        String programIdentifier,
        String callbackIdentifier,
        Kind kind,
        Throwable cause) {

    public MindFailure {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(programIdentifier, "programIdentifier");
        Objects.requireNonNull(callbackIdentifier, "callbackIdentifier");
        Objects.requireNonNull(kind, "kind");
    }

    public enum Kind {
        CALLBACK_EXCEPTION,
        RUNAWAY_LIMIT_EXCEEDED,
        /** @deprecated Soft callback overruns now backpressure later work. */
        @Deprecated
        CALLBACK_TIME_EXCEEDED,
        /** @deprecated Entity budget exhaustion is scheduler backpressure, not failure. */
        @Deprecated
        ENTITY_BUDGET_EXHAUSTED,
        /** @deprecated Server budget exhaustion is scheduler backpressure, not failure. */
        @Deprecated
        SERVER_BUDGET_EXHAUSTED
    }
}
