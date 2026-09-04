package com.magmaguy.magmacore.ai;

import java.util.Objects;

/** Last observed native body movement while a destination was active. */
public record MindNavigationProgress(long gameTick, MindPosition position) {
    public MindNavigationProgress {
        Objects.requireNonNull(position, "position");
    }
}
