package com.magmaguy.easyminecraftgoals.mindshared.mind;

import com.magmaguy.magmacore.ai.MindBehavior;
import com.magmaguy.magmacore.ai.MindControl;
import com.magmaguy.magmacore.ai.MindStopReason;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

final class NativeMindBehaviorControl implements BehaviorControl<Mob> {
    private final NativeMindProgramRuntime runtime;
    private final MindBehavior behavior;
    private final NativeMindControlLeaseBook leases;
    private final Set<MindControl> controls;
    private final NativeMindProbeSchedule probes;
    // Built once: callbacks run several times a tick for every entity.
    private final String canStartIdentifier, startIdentifier, canContinueIdentifier, tickIdentifier, stopIdentifier;
    private Behavior.Status status = Behavior.Status.STOPPED;

    NativeMindBehaviorControl(
            NativeMindProgramRuntime runtime,
            MindBehavior behavior,
            NativeMindControlLeaseBook leases) {
        this.runtime = runtime;
        this.behavior = behavior;
        this.leases = leases;
        Set<MindControl> declared = behavior.controls();
        controls = declared.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(declared));
        String identifier = behavior.identifier();
        int probeInterval = Math.min(MindBehavior.MAX_PROBE_INTERVAL, behavior.probeInterval());
        probes = new NativeMindProbeSchedule(probeInterval, runtime.logicalOwner(), identifier);
        canStartIdentifier = identifier + ".can_start";
        startIdentifier = identifier + ".start";
        canContinueIdentifier = identifier + ".can_continue";
        tickIdentifier = identifier + ".tick";
        stopIdentifier = identifier + ".stop";
    }

    String identifier() {
        return behavior.identifier();
    }

    int priority() {
        return behavior.priority();
    }

    boolean running() {
        return status == Behavior.Status.RUNNING;
    }

    @Override
    public Behavior.Status getStatus() {
        return status;
    }

    public Set<MemoryModuleType<?>> getRequiredMemories() {
        return Set.of();
    }

    /**
     * The Brain offers every stopped behavior a start each tick; this one asks its {@code canStart} only on the ticks
     * of its probe grid. A probe the scheduler skips for budget, or a start it defers, is asked again on the next
     * tick, and the ask after that is back on the grid.
     */
    @Override
    public boolean tryStart(ServerLevel level, Mob body, long timestamp) {
        if (!probes.due(timestamp)) return false;
        NativeMindCallbackScheduler.CallbackResult<Boolean> allowed = runtime.invoke(
                canStartIdentifier,
                () -> behavior.canStart(runtime.context(this)));
        if (allowed.wasSkipped()) return false;
        probes.ran(timestamp);
        if (!allowed.successful() || !Boolean.TRUE.equals(allowed.value())) return false;
        if (!leases.acquire(this, controls)) return false;

        status = Behavior.Status.RUNNING;
        NativeMindCallbackScheduler.CallbackResult<Void> started = runtime.invoke(
                startIdentifier,
                () -> {
                    behavior.start(runtime.context(this));
                    return null;
                });
        if (started.wasSkipped()) {
            status = Behavior.Status.STOPPED;
            leases.release(this);
            probes.retry(timestamp);
            return false;
        }
        if (!started.successful()) {
            stop(MindStopReason.CALLBACK_FAILED);
            return false;
        }
        return true;
    }

    @Override
    public void tickOrStop(ServerLevel level, Mob body, long timestamp) {
        if (!running()) return;
        NativeMindCallbackScheduler.CallbackResult<Boolean> continuing = runtime.invoke(
                canContinueIdentifier,
                () -> behavior.canContinue(runtime.context(this)));
        if (continuing.wasSkipped()) return;
        if (!continuing.successful()) {
            stop(MindStopReason.CALLBACK_FAILED);
            return;
        }
        if (!Boolean.TRUE.equals(continuing.value())) {
            stop(MindStopReason.COMPLETED);
            return;
        }

        NativeMindCallbackScheduler.CallbackResult<Void> ticked = runtime.invoke(
                tickIdentifier,
                () -> {
                    behavior.tick(runtime.context(this));
                    return null;
                });
        if (!ticked.successful() && !ticked.wasSkipped()) {
            stop(MindStopReason.CALLBACK_FAILED);
        }
    }

    @Override
    public void doStop(ServerLevel level, Mob body, long timestamp) {
        stop(runtime.externalStopReason());
    }

    void preempt() {
        stop(MindStopReason.PREEMPTED);
    }

    private void stop(MindStopReason reason) {
        if (!running()) {
            leases.release(this);
            return;
        }
        try {
            runtime.invoke(stopIdentifier, () -> {
                behavior.stop(runtime.context(this), reason);
                return null;
            });
        } finally {
            runtime.releaseControls(this);
            status = Behavior.Status.STOPPED;
            leases.release(this);
            // Next asked on its own grid, so a crowd that stops together does not start asking together.
            probes.stopped(runtime.gameTick());
        }
    }

    @Override
    public String debugString() {
        return "MagmaCoreBehavior[" + identifier() + "]";
    }
}
