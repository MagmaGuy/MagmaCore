package com.magmaguy.magmacore.ai;

public interface MindSensor {
    String identifier();

    /**
     * How often, in ticks, the sensor senses. Each entity senses on its own phase of the interval, so entities created
     * together do not sense on the same tick; the first run comes up to interval - 1 ticks after the host starts
     * ticking the body. A run the host defers for budget is repeated on the next tick.
     */
    default int intervalTicks() {
        return 1;
    }

    void sense(MindContext context);
}
