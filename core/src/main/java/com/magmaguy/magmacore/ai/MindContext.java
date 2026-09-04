package com.magmaguy.magmacore.ai;

import org.bukkit.entity.LivingEntity;

import java.util.Objects;

public record MindContext(
        LivingEntity entity,
        long gameTick,
        long generation,
        MindMemoryView memory,
        MindPerception perception,
        MindActuator actuator,
        MindActionRequester actions) {

    public MindContext {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(perception, "perception");
        Objects.requireNonNull(actuator, "actuator");
        Objects.requireNonNull(actions, "actions");
    }

    /** Source-compatible constructor for callers that do not expose host actions. */
    public MindContext(
            LivingEntity entity,
            long gameTick,
            long generation,
            MindMemoryView memory,
            MindPerception perception,
            MindActuator actuator) {
        this(
                entity,
                gameTick,
                generation,
                memory,
                perception,
                actuator,
                MindActionRequester.rejecting());
    }
}
