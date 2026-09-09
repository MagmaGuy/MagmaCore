package com.magmaguy.easyminecraftgoals.mindshared.mind;

import com.magmaguy.magmacore.ai.MindFailure;
import com.magmaguy.magmacore.ai.MindActionRequest;
import com.magmaguy.magmacore.ai.MindActionResult;
import com.magmaguy.magmacore.ai.MindHandle;
import com.magmaguy.magmacore.ai.MindMemoryStore;
import com.magmaguy.magmacore.ai.MindPersistentState;
import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.ai.MindSnapshot;
import com.magmaguy.magmacore.ai.MindStopReason;
import com.magmaguy.magmacore.ai.MobBody;
import com.magmaguy.magmacore.ai.StateTransfer;
import com.magmaguy.magmacore.ai.SwapReceipt;
import com.magmaguy.easyminecraftgoals.TransientMovementOverride;
import org.bukkit.Location;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class NativeMindSession implements MindHandle {
    private final NativeMindHost host;
    private final UUID logicalOwner;
    private final NativeMindServerBudget serverBudget;
    private final Deque<SwapRequest> swaps = new ArrayDeque<>();
    private final Deque<NativeMindFleeOverride> movementOverrides = new ArrayDeque<>();
    private NativeMindPathfindingHandle pathfinding;
    private final NativeMindMovementOverrideGate movementOverrideGate =
            new NativeMindMovementOverrideGate();
    private MindProgram program;
    private MindMemoryStore memory;
    private MindPersistentState lastWrittenState;
    private long generation = 1L;
    private long lastRequestedGeneration = 1L;
    private NativeMindBody body;
    private NativeMindProgramRuntime runtime;
    private boolean inBrainTick;
    private boolean inLifecycleBoundary;
    private boolean detachRequested;
    private boolean preserveMarkerOnDetach;
    private boolean closeRequested;
    private boolean preserveMarkerOnClose;
    private Boolean requestedPause;
    private boolean paused;
    private long pausedAtGameTick = Long.MIN_VALUE;
    private boolean closed;

    NativeMindSession(
            NativeMindHost host,
            UUID logicalOwner,
            MindProgram program,
            MindPersistentState restoredState,
            NativeMindServerBudget serverBudget) {
        this.host = host;
        this.logicalOwner = logicalOwner;
        this.program = program;
        this.serverBudget = serverBudget;
        memory = new MindMemoryStore(program.schema());
        memory.restore(restoredState);
    }

    UUID logicalOwner() {
        return logicalOwner;
    }

    boolean hasBody(UUID bodyId) {
        return body != null && body.mob().getUUID().equals(bodyId);
    }

    Optional<TransientMovementOverride> beginFlee(
            Location threatLocation,
            double speedModifier) {
        requireOpen();
        if (!Double.isFinite(speedModifier) || speedModifier <= 0D) return Optional.empty();
        if (body == null || !(body.mob() instanceof net.minecraft.world.entity.PathfinderMob pathfinderMob)) {
            return Optional.empty();
        }
        net.minecraft.world.level.pathfinder.Path initialPath =
                com.magmaguy.easyminecraftgoals.mindshared.flee.FleePathfinder.findPath(
                        pathfinderMob, threatLocation);
        if (initialPath == null) return Optional.empty();
        NativeMindFleeOverride override;
        try {
            override = new NativeMindFleeOverride(
                    this,
                    pathfinderMob,
                    threatLocation,
                    speedModifier,
                    initialPath,
                    movementOverrideGate.acquire());
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        movementOverrides.addLast(override);
        return Optional.of(override);
    }

    boolean movementOverridden() {
        return movementOverrideGate.isOverridden();
    }

    Optional<com.magmaguy.easyminecraftgoals.PathfindingHandle> openPathfinding() {
        requireOpen();
        if (body == null) return Optional.empty();
        if (pathfinding != null) pathfinding.close();
        pathfinding = new NativeMindPathfindingHandle(this, body, movementOverrideGate);
        return Optional.of(pathfinding);
    }

    void releasePathfinding(NativeMindPathfindingHandle handle) {
        if (pathfinding == handle) pathfinding = null;
    }

    void removeMovementOverride(NativeMindFleeOverride override) {
        movementOverrides.remove(override);
    }

    @Override
    public void attachBody(MobBody nextBody) {
        NativeMindHost.requirePrimaryThread();
        requireOpen();
        if (!(nextBody instanceof NativeMindBody nativeBody) || nativeBody.host() != host) {
            throw new IllegalArgumentException("Body was not created by this native mind host");
        }
        if (body != null) throw new IllegalStateException("Logical mind session already has a body");
        if (nativeBody.attached()) throw new IllegalStateException("Native mind body is already attached");
        if (!nativeBody.canAttach()) throw new IllegalArgumentException("Native mind body is not valid");

        applyDetachedSwaps();
        NativeMindProgramRuntime candidate = new NativeMindProgramRuntime(
                this,
                nativeBody,
                program,
                memory,
                generation,
                serverBudget);
        candidate.buildBrain();

            body = nativeBody;
            runtime = candidate;
            try {
                nativeBody.attach(this);
                candidate.actuatorStopAll();
                if (paused && pausedAtGameTick == Long.MIN_VALUE) {
                    pausedAtGameTick = nativeBody.level().getGameTime();
                    memory.view(pausedAtGameTick);
                    nativeBody.stopMovement();
                }
                writeMarker(nativeBody);
        } catch (RuntimeException exception) {
            nativeBody.detach(this);
            body = null;
            runtime = null;
            throw exception;
        }
    }

    @Override
    public void detachBody() {
        NativeMindHost.requirePrimaryThread();
        if (closed || body == null) return;
        if (inBrainTick || inLifecycleBoundary) {
            requestDetach(false);
            return;
        }
        detachNow(MindStopReason.ENTITY_REMOVED, false);
        finishDeferredAfterDetach();
    }

    @Override
    public void suspendBody() {
        NativeMindHost.requirePrimaryThread();
        if (closed || body == null) return;
        if (inBrainTick || inLifecycleBoundary) {
            requestDetach(true);
            return;
        }
        detachNow(MindStopReason.ENTITY_REMOVED, true);
        finishDeferredAfterDetach();
    }

    @Override
    public void setPaused(boolean paused) {
        NativeMindHost.requirePrimaryThread();
        requireOpen();
        if (inBrainTick || inLifecycleBoundary) {
            requestedPause = paused;
            return;
        }
        applyPause(paused);
    }

    @Override
    public SwapReceipt replace(MindProgram next, StateTransfer transfer) {
        NativeMindHost.requirePrimaryThread();
        requireOpen();
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(transfer, "transfer");
        next.claimForBinding();

        long requested = incrementRequestedGeneration();
        swaps.addLast(new SwapRequest(requested, next, transfer));
        if (body == null) {
            applyDetachedSwaps();
            return new SwapReceipt(requested, false);
        }
        return new SwapReceipt(requested, true);
    }

    @Override
    public MindSnapshot inspect() {
        NativeMindHost.requirePrimaryThread();
        Set<String> active = runtime == null ? Set.of() : runtime.activeBehaviors();
        return new MindSnapshot(
                logicalOwner,
                body == null ? Optional.empty() : Optional.of(body.entity().getUniqueId()),
                program.identifier(),
                program.revision(),
                generation,
                active,
                memory.persistentState(),
                requestedPause == null ? paused : requestedPause,
                closed);
    }

    @Override
    public void close() {
        NativeMindHost.requirePrimaryThread();
        if (closed) return;
        if (inBrainTick || inLifecycleBoundary) {
            requestClose(false);
            return;
        }
        closeNow(false);
    }

    void closeForShutdown() {
        NativeMindHost.requirePrimaryThread();
        if (closed) return;
        if (inBrainTick || inLifecycleBoundary) {
            requestClose(true);
            return;
        }
        closeNow(true);
    }

    void tickBody() {
        if (closed || body == null || runtime == null) return;
        NativeMindBody tickingBody = body;
        Mob mob = tickingBody.mob();
        if (mob.isRemoved()) {
            Entity.RemovalReason reason = tickingBody.removalReason();
            bodyRemoved(mob, reason == null ? Entity.RemovalReason.DISCARDED : reason);
            return;
        }
        ServerLevel level = tickingBody.level();
        if (inBrainTick) throw new IllegalStateException("Native mind Brain tick is reentrant");

        inBrainTick = true;
        try {
            applyAttachedSwaps(level);
            if (runtime == null || body == null) return;
            NativeMindFleeOverride movementOverride = activeMovementOverride();
            if (pathfinding != null) pathfinding.tick(paused || movementOverride != null);
            if (paused && movementOverride == null) {
                tickingBody.tickPausedPhysics();
                writeMarkerIfChanged(tickingBody);
                return;
            }
            tickingBody.beforeMindTick();
            try {
                if (!paused) {
                    runtime.beginTick(level);
                    runtime.tickBrain(level);
                }
                movementOverride = activeMovementOverride();
                if (movementOverride != null) movementOverride.tick();
            } finally {
                tickingBody.afterMindTick();
            }
            writeMarkerIfChanged(tickingBody);
        } finally {
            inBrainTick = false;
            applyRequestedPause();
            if (closeRequested) {
                closeRequested = false;
                boolean preserveMarker = preserveMarkerOnClose;
                preserveMarkerOnClose = false;
                closeNow(preserveMarker);
            } else if (detachRequested) {
                detachRequested = false;
                boolean preserveMarker = preserveMarkerOnDetach;
                preserveMarkerOnDetach = false;
                detachNow(MindStopReason.ENTITY_REMOVED, preserveMarker);
                finishDeferredAfterDetach();
            }
        }
    }

    void bodyRemoved(Mob removedBody, Entity.RemovalReason reason) {
        if (closed || body == null || body.mob() != removedBody) return;
        if (inBrainTick || inLifecycleBoundary) {
            requestDetach(reason.shouldSave());
            return;
        }
        detachNow(MindStopReason.ENTITY_REMOVED, reason.shouldSave());
        finishDeferredAfterDetach();
    }

    void report(MindFailure failure) {
        host.report(failure);
    }

    MindActionResult requestAction(
            NativeMindBody body,
            long gameTick,
            long generation,
            MindActionRequest request) {
        return host.requestAction(this, body, gameTick, generation, request);
    }

    private void applyAttachedSwaps(ServerLevel level) {
        if (swaps.isEmpty()) return;
        List<SwapRequest> pending = new ArrayList<>(swaps);
        Migration preflight = migrate(pending);
        new NativeMindProgramRuntime(
                this,
                body,
                preflight.program(),
                preflight.memory(),
                preflight.generation(),
                serverBudget).buildBrain();

        runtime.retire(level, MindStopReason.PROGRAM_REPLACED);
        runtime.actuatorStopAll();
        Migration migration = migrate(pending);
        NativeMindProgramRuntime candidate = new NativeMindProgramRuntime(
                this,
                body,
                migration.program(),
                migration.memory(),
                migration.generation(),
                serverBudget);
        candidate.buildBrain();
        program = migration.program();
        memory = migration.memory();
        generation = migration.generation();
        runtime = candidate;
        removeAppliedSwaps(pending);
        writeMarker(body);
    }

    private void applyDetachedSwaps() {
        if (swaps.isEmpty()) return;
        Migration migration = migrate(new ArrayList<>(swaps));
        program = migration.program();
        memory = migration.memory();
        generation = migration.generation();
        swaps.clear();
    }

    private Migration migrate(List<SwapRequest> pending) {
        MindProgram nextProgram = program;
        MindMemoryStore nextMemory = memory;
        long nextGeneration = generation;
        for (SwapRequest request : pending) {
            nextMemory = nextMemory.transferTo(request.program().schema(), request.transfer());
            nextProgram = request.program();
            nextGeneration = request.generation();
        }
        return new Migration(nextProgram, nextMemory, nextGeneration);
    }

    private void detachNow(MindStopReason reason, boolean preserveMarker) {
        NativeMindBody oldBody = body;
        NativeMindProgramRuntime oldRuntime = runtime;
        if (oldBody == null) return;

        inLifecycleBoundary = true;
        try {
            closeMovementOverrides();
            ServerLevel level = (ServerLevel) oldBody.mob().level();
            if (oldRuntime != null) {
                oldRuntime.retire(level, reason);
                oldRuntime.actuatorStopAll();
            }
            applyDetachedSwaps();
            oldBody.stopMovement();
            boolean retain = preserveMarker;
            if (detachRequested) retain &= preserveMarkerOnDetach;
            if (closeRequested) retain &= preserveMarkerOnClose;
            if (retain) writeMarker(oldBody);
            else MindCarrierState.clear(oldBody.entity(), host.hostIdentity());
            oldBody.detach(this);
            body = null;
            runtime = null;
        } finally {
            inLifecycleBoundary = false;
        }
    }

    private void applyRequestedPause() {
        if (requestedPause == null || closed) return;
        boolean next = requestedPause;
        requestedPause = null;
        applyPause(next);
    }

    private void applyPause(boolean next) {
        if (paused == next) return;
        if (next) {
            paused = true;
            if (body != null) {
                pausedAtGameTick = body.level().getGameTime();
                memory.view(pausedAtGameTick);
                if (runtime != null) runtime.actuatorStopAll();
                body.stopMovement();
            }
            return;
        }
        if (pausedAtGameTick != Long.MIN_VALUE && body != null) {
            long currentGameTick = body.level().getGameTime();
            memory.shiftClock(Math.max(0L, currentGameTick - pausedAtGameTick));
        }
        pausedAtGameTick = Long.MIN_VALUE;
        paused = false;
    }

    private void closeNow(boolean preserveMarker) {
        if (closed) return;
        if (body != null) detachNow(MindStopReason.HANDLE_CLOSED, preserveMarker);
        else {
            closeMovementOverrides();
            applyDetachedSwaps();
        }
        swaps.clear();
        requestedPause = null;
        pausedAtGameTick = Long.MIN_VALUE;
        closeRequested = false;
        detachRequested = false;
        closed = true;
        host.sessionClosed(this);
    }

    private void finishDeferredAfterDetach() {
        if (closeRequested) {
            boolean preserveMarker = preserveMarkerOnClose;
            closeRequested = false;
            preserveMarkerOnClose = false;
            closeNow(preserveMarker);
        } else {
            detachRequested = false;
            preserveMarkerOnDetach = false;
        }
    }

    private void requestDetach(boolean preserveMarker) {
        if (!detachRequested) preserveMarkerOnDetach = preserveMarker;
        else preserveMarkerOnDetach &= preserveMarker;
        detachRequested = true;
    }

    private void requestClose(boolean preserveMarker) {
        if (!closeRequested) preserveMarkerOnClose = preserveMarker;
        else preserveMarkerOnClose &= preserveMarker;
        closeRequested = true;
    }

    private void removeAppliedSwaps(List<SwapRequest> applied) {
        for (SwapRequest expected : applied) {
            if (swaps.peekFirst() != expected) {
                throw new IllegalStateException("Mind swap queue order changed during a safe boundary");
            }
            swaps.removeFirst();
        }
    }

    private void writeMarker(NativeMindBody target) {
        MindPersistentState state = memory.persistentState();
        MindCarrierState.write(
                target.entity(),
                host.hostIdentity(),
                target.profile(),
                logicalOwner,
                program.identifier(),
                program.revision(),
                state);
        lastWrittenState = state;
    }

    private void writeMarkerIfChanged(NativeMindBody target) {
        MindPersistentState current = memory.persistentState();
        if (current.equals(lastWrittenState)) return;
        writeMarker(target);
    }

    private long incrementRequestedGeneration() {
        if (lastRequestedGeneration == Long.MAX_VALUE) {
            throw new IllegalStateException("Mind generation is exhausted");
        }
        return ++lastRequestedGeneration;
    }

    private NativeMindFleeOverride activeMovementOverride() {
        while (!movementOverrides.isEmpty()) {
            NativeMindFleeOverride active = movementOverrides.peekLast();
            if (active.isActive()) return active;
            movementOverrides.removeLast();
        }
        return null;
    }

    private void closeMovementOverrides() {
        if (pathfinding != null) pathfinding.close();
        for (NativeMindFleeOverride movementOverride : new ArrayList<>(movementOverrides)) {
            movementOverride.close();
        }
        movementOverrides.clear();
    }

    private void requireOpen() {
        if (closed || closeRequested) throw new IllegalStateException("Logical mind session is closed");
    }

    private record SwapRequest(long generation, MindProgram program, StateTransfer transfer) {
    }

    private record Migration(MindProgram program, MindMemoryStore memory, long generation) {
    }
}
