package com.magmaguy.magmacore.ai;

import java.util.Set;

public interface MindBehavior {
    /** The longest {@link #probeInterval()}, in ticks: ten seconds. */
    int MAX_PROBE_INTERVAL = 200;
    /** The longest {@link #tickInterval()}, in ticks: ten seconds, as for {@link #MAX_PROBE_INTERVAL}. */
    int MAX_TICK_INTERVAL = 200;

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
     * host defers for budget is repeated on the next tick. A running behavior's {@link #canContinue} and
     * {@link #tick} follow {@link #tickInterval()} instead, and {@link #stop} runs when it happens. A behavior asked
     * less often starts, and preempts a lower-priority one, up to N - 1 ticks after it could; one that stops is asked
     * again up to N ticks later. 1 to {@link #MAX_PROBE_INTERVAL}.
     */
    default int probeInterval() {
        return 1;
    }

    /**
     * How often, in ticks, a running behavior's {@link #canContinue} and {@link #tick} run: 1 runs them every tick, N
     * every Nth tick. They run on the tick the behavior starts, then on the entity's own phase of the interval, so the
     * second run comes up to N ticks after the first and a crowd that started together does not keep running together.
     * A run the host defers for budget is repeated on the next tick. Between runs the behavior keeps its controls but
     * renews nothing: a path from {@link MindActuator#moveTo} is still followed, a look from
     * {@link MindActuator#lookAt} lets go after 2 ticks, and {@link MindActuator#moveDirect} and
     * {@link MindActuator#steerFlight} steer for one tick. It also sees that it should stop up to N - 1 ticks late.
     * {@link #stop}, and preemption by a behavior that comes first, are never delayed. 1 to
     * {@link #MAX_TICK_INTERVAL}.
     */
    default int tickInterval() {
        return 1;
    }

    /**
     * Whether this stopped behavior starts now. The host asks only while the behavior could take its
     * {@link #controls()}: while a running behavior that comes first (a lower {@link #priority()}, then the identifier)
     * holds one of them, that probe ends without asking. A search, a chance roll or a timer moved forward here is
     * therefore never spent on a start the controls would refuse.
     */
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
