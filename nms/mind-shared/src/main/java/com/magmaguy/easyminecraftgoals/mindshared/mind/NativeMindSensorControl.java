package com.magmaguy.easyminecraftgoals.mindshared.mind;

import com.magmaguy.magmacore.ai.MindSensor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.Set;

/** Runs a plugin sensor from the Brain's CORE activity without registering a Mojang SensorType. */
final class NativeMindSensorControl implements BehaviorControl<Mob> {
    private final NativeMindProgramRuntime runtime;
    private final MindSensor sensor;
    private final int intervalTicks;
    private long nextTick = Long.MIN_VALUE;

    NativeMindSensorControl(NativeMindProgramRuntime runtime, MindSensor sensor) {
        this.runtime = runtime;
        this.sensor = sensor;
        this.intervalTicks = sensor.intervalTicks();
        if (intervalTicks <= 0) {
            throw new IllegalArgumentException(
                    "Mind sensor interval must be positive: " + sensor.identifier());
        }
    }

    @Override
    public Behavior.Status getStatus() {
        return Behavior.Status.STOPPED;
    }

    public Set<MemoryModuleType<?>> getRequiredMemories() {
        return Set.of();
    }

    @Override
    public boolean tryStart(ServerLevel level, Mob body, long timestamp) {
        if (timestamp < nextTick) return false;
        NativeMindCallbackScheduler.CallbackResult<Void> sensed = runtime.invoke(
                sensor.identifier() + ".sense", () -> {
            sensor.sense(runtime.context(null));
            return null;
        });
        if (!sensed.wasSkipped()) {
            nextTick = timestamp > Long.MAX_VALUE - intervalTicks
                    ? Long.MAX_VALUE
                    : timestamp + intervalTicks;
        }
        return false;
    }

    @Override
    public void tickOrStop(ServerLevel level, Mob body, long timestamp) {
    }

    @Override
    public void doStop(ServerLevel level, Mob body, long timestamp) {
    }

    @Override
    public String debugString() {
        return "MagmaCoreSensor[" + sensor.identifier() + "]";
    }
}
