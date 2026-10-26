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
     * tick, N every Nth tick. Each entity's asks fall on their own phase, so a crowd of entities with the same
     * behavior does not ask on the same tick. A running behavior's {@link #canContinue}, {@link #tick} and
     * {@link #stop} still run every tick, and a behavior that stops may be asked again on the next tick. A behavior
     * asked less often starts, and preempts a lower-priority one, up to N - 1 ticks later. 1 to
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
