package com.magmaguy.magmacore.ai;

import java.util.Objects;
import java.util.UUID;

/** Current logical and physical context supplied to a host action sink. */
public record MindActionContext(
        UUID logicalOwner,
        MobBody body,
        long gameTick,
        long generation) {

    public MindActionContext {
        Objects.requireNonNull(logicalOwner, "logicalOwner");
        Objects.requireNonNull(body, "body");
        if (generation < 1L) throw new IllegalArgumentException("generation must be positive");
    }
}
