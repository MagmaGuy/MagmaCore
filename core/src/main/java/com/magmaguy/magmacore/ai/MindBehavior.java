package com.magmaguy.magmacore.ai;

import java.util.Set;

public interface MindBehavior {
    String identifier();

    default int priority() {
        return 0;
    }

    default Set<MindControl> controls() {
        return Set.of();
    }

    boolean canStart(MindContext context);

    default boolean canContinue(MindContext context) {
        return canStart(context);
    }

    default void start(MindContext context) {
    }

    void tick(MindContext context);

    default void stop(MindContext context, MindStopReason reason) {
    }
}
