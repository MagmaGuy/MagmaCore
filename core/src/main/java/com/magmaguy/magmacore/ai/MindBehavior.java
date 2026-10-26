package com.magmaguy.magmacore.ai;

import java.util.Set;

public interface MindBehavior {
    /** The longest {@link #probeInterval()}, in ticks: ten seconds. */
    int MAX_PROBE_INTERVAL = 200;

    String identifier();

    default int priority() {
        return 0;
    }

    default Set<MindControl> controls() {
        return Set.of();
    }

    /**
     * How often, in ticks, the host asks a stopped behavior's {@link #canStart} whether it may start: 1 asks every
     * tick, N every Nth tick. Each entity asks on its own phase of the interval and keeps to it, so a crowd of
     * entities with the same behavior does not ask on the same tick, even after they all stop it together. An ask the
     * host defers for budget is repeated on the next tick. A running behavior's {@link #canContinue}, {@link #tick}
     * and {@link #stop} still run every tick. A behavior asked less often starts, and preempts a lower-priority one,
     * up to N - 1 ticks after it could; one that stops is asked again up to N ticks later. 1 to
     * {@link #MAX_PROBE_INTERVAL}.
     */
    default int probeInterval() {
        return 1;
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
