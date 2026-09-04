package com.magmaguy.magmacore.ai;

public interface MindSensor {
    String identifier();

    default int intervalTicks() {
        return 1;
    }

    void sense(MindContext context);
}
