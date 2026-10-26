package com.magmaguy.magmacore.ai;

public interface MindSensor {
    String identifier();

    /**
     * How often, in ticks, the sensor senses. It first senses on the first tick the host ticks the body, after a spawn,
     * a chunk load or a program swap. From its second run on, each entity senses on its own phase of the interval, so
     * entities created together do not keep sensing on the same tick; the second run comes up to one interval after
     * the first. A run the host defers for budget is repeated on the next tick.
     */
    default int intervalTicks() {
        return 1;
    }

    void sense(MindContext context);
}
