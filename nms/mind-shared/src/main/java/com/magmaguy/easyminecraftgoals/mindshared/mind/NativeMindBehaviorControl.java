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

    @Override
    public boolean tryStart(ServerLevel level, Mob body, long timestamp) {
        NativeMindCallbackScheduler.CallbackResult<Boolean> allowed = runtime.invoke(
                identifier() + ".can_start",
                () -> behavior.canStart(runtime.context(this)));
        if (!allowed.successful() || !Boolean.TRUE.equals(allowed.value())) return false;
        if (!leases.acquire(this, controls)) return false;

        status = Behavior.Status.RUNNING;
        NativeMindCallbackScheduler.CallbackResult<Void> started = runtime.invoke(
                identifier() + ".start",
                () -> {
                    behavior.start(runtime.context(this));
                    return null;
                });
        if (started.wasSkipped()) {
            status = Behavior.Status.STOPPED;
            leases.release(this);
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
                identifier() + ".can_continue",
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
                identifier() + ".tick",
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
            runtime.invoke(identifier() + ".stop", () -> {
                behavior.stop(runtime.context(this), reason);
                return null;
            });
        } finally {
            runtime.releaseControls(this);
            status = Behavior.Status.STOPPED;
            leases.release(this);
        }
    }

    @Override
    public String debugString() {
        return "MagmaCoreBehavior[" + identifier() + "]";
    }
}
