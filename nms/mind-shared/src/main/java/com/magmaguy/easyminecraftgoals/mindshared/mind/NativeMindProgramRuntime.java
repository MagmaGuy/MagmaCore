package com.magmaguy.easyminecraftgoals.mindshared.mind;

import com.google.common.collect.ImmutableList;
import com.magmaguy.magmacore.ai.MindBehavior;
import com.magmaguy.magmacore.ai.MindActionRequest;
import com.magmaguy.magmacore.ai.MindActionResult;
import com.magmaguy.magmacore.ai.MindControl;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindFailure;
import com.magmaguy.magmacore.ai.MindMemoryStore;
import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.ai.MindSensor;
import com.magmaguy.magmacore.ai.MindStopReason;
import com.mojang.datafixers.util.Pair;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.schedule.Activity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

final class NativeMindProgramRuntime {
    private final NativeMindSession session;
    private final NativeMindBody body;
    private final MindProgram program;
    private final MindMemoryStore memory;
    private final long generation;
    private final NativeMindControlLeaseBook leases = new NativeMindControlLeaseBook();
    private final NativeMindNavigationTracker navigation;
    private final NativeMindActuator actuator;
    private final NativeMindPerception perception;
    private final NativeMindCallbackScheduler callbacks;
    private final List<NativeMindBehaviorControl> behaviors = new ArrayList<>();
    private Brain<Mob> brain;
    private long gameTick;
    private int actionRequestCount;
    private MindStopReason externalStopReason;

    NativeMindProgramRuntime(
            NativeMindSession session,
            NativeMindBody body,
            MindProgram program,
            MindMemoryStore memory,
            long generation,
            NativeMindServerBudget serverBudget) {
        this.session = session;
        this.body = body;
        this.program = program;
        this.memory = memory;
        this.generation = generation;
        this.navigation = new NativeMindNavigationTracker(body.mob());
        this.actuator = new NativeMindActuator(body, navigation, session::movementOverridden);
        this.perception = new NativeMindPerception(body.mob(), navigation);
        this.callbacks = new NativeMindCallbackScheduler(
                program.executionPolicy(),
                serverBudget,
                this::report);
    }

    Brain<Mob> buildBrain() {
        Brain<Mob> next = NativeMindVersion.newBrain();
        List<Pair<Integer, ? extends BehaviorControl<? super Mob>>> sensorControls =
                new ArrayList<>();
        for (MindSensor sensor : program.sensors()) {
            sensorControls.add(Pair.of(
                    Integer.MIN_VALUE,
                    new NativeMindSensorControl(this, sensor)));
        }

        List<Pair<Integer, ? extends BehaviorControl<? super Mob>>> behaviorControls =
                new ArrayList<>();
        for (MindBehavior behavior : program.behaviors()) {
            NativeMindBehaviorControl control = new NativeMindBehaviorControl(this, behavior, leases);
            behaviors.add(control);
            behaviorControls.add(Pair.of(behavior.priority(), control));
        }

        NativeMindVersion.addActivity(next, Activity.CORE, ImmutableList.copyOf(sensorControls));
        NativeMindVersion.addActivity(next, Activity.IDLE, ImmutableList.copyOf(behaviorControls));
        next.setCoreActivities(Set.of(Activity.CORE));
        next.setDefaultActivity(Activity.IDLE);
        next.useDefaultActivity();
        brain = next;
        return next;
    }

    void tickBrain(ServerLevel currentLevel) {
        if (brain == null) throw new IllegalStateException("Native Mind Brain is not built");
        brain.tick(currentLevel, body.mob());
    }

    void beginTick(ServerLevel currentLevel) {
        gameTick = currentLevel.getGameTime();
        actionRequestCount = 0;
        callbacks.beginTick(currentLevel.getServer().getTickCount());
        memory.view(gameTick);
        navigation.beginTick(gameTick);
    }

    MindContext context(NativeMindBehaviorControl owner) {
        return new MindContext(
                body.entity(),
                gameTick,
                generation,
                memory.view(gameTick),
                perception,
                actuator.guarded(owner, leases),
                request -> requestAction(owner, request));
    }

    private MindActionResult requestAction(
            NativeMindBehaviorControl owner,
            MindActionRequest request) {
        NativeMindHost.requirePrimaryThread();
        if (owner == null || !leases.holds(owner, MindControl.ACTION)) {
            throw new IllegalStateException(
                    "Mind behavior must declare and hold the ACTION control");
        }
        if (actionRequestCount >= program.executionPolicy().maxActionRequestsPerEntityTick()) {
            return MindActionResult.DEFERRED;
        }
        actionRequestCount++;
        return session.requestAction(body, gameTick, generation, request);
    }

    <T> NativeMindCallbackScheduler.CallbackResult<T> invoke(
            String callbackIdentifier,
            Supplier<T> callback) {
        return callbacks.invoke(callbackIdentifier, callback);
    }

    void retire(ServerLevel currentLevel, MindStopReason reason) {
        if (brain == null) return;
        gameTick = currentLevel.getGameTime();
        callbacks.beginTick(currentLevel.getServer().getTickCount());
        externalStopReason = reason;
        try {
            brain.stopAll(currentLevel, body.mob());
        } finally {
            brain.removeAllBehaviors();
            externalStopReason = null;
            leases.clear();
            actuator.stopAll();
        }
    }

    void actuatorStopAll() {
        actuator.stopAll();
    }

    void releaseControls(NativeMindBehaviorControl owner) {
        actuator.guarded(owner, leases).stopAll();
    }

    MindStopReason externalStopReason() {
        return externalStopReason == null ? MindStopReason.COMPLETED : externalStopReason;
    }

    Set<String> activeBehaviors() {
        Set<String> active = new LinkedHashSet<>();
        for (NativeMindBehaviorControl behavior : behaviors) {
            if (behavior.running()) active.add(behavior.identifier());
        }
        return active;
    }

    private void report(String callbackIdentifier, MindFailure.Kind kind, Throwable cause) {
        session.report(new MindFailure(
                session.logicalOwner(),
                program.identifier(),
                callbackIdentifier,
                kind,
                cause));
    }

}
