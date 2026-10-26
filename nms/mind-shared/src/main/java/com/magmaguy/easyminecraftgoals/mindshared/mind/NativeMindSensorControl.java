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
    private final String senseIdentifier;
    private final NativeMindProbeSchedule schedule;

    NativeMindSensorControl(NativeMindProgramRuntime runtime, MindSensor sensor) {
        this.runtime = runtime;
        this.sensor = sensor;
        this.intervalTicks = sensor.intervalTicks();
        this.senseIdentifier = sensor.identifier() + ".sense";
        if (intervalTicks <= 0) {
            throw new IllegalArgumentException(
                    "Mind sensor interval must be positive: " + sensor.identifier());
        }
        // The first run is at once, as before, for sensors that set things up on spawn. After it each entity senses on
        // its own phase of the interval, so a flock spawned together does not keep sensing together.
        this.schedule = new NativeMindProbeSchedule(intervalTicks, runtime.logicalOwner(), sensor.identifier(), true);
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
        if (!schedule.due(timestamp)) return false;
        NativeMindCallbackScheduler.CallbackResult<Void> sensed = runtime.invoke(
                senseIdentifier, () -> {
            sensor.sense(runtime.context(null));
            return null;
        });
        // A run the scheduler skips for budget is tried again on the next tick.
        if (!sensed.wasSkipped()) schedule.ran(timestamp);
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
