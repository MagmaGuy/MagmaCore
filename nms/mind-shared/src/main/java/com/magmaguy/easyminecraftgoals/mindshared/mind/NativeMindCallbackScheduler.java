package com.magmaguy.easyminecraftgoals.mindshared.mind;

import com.magmaguy.magmacore.ai.MindExecutionPolicy;
import com.magmaguy.magmacore.ai.MindFailure;
import com.magmaguy.magmacore.ai.MindRunawayException;

import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Applies soft callback, entity, and shared-server admission budgets for one Mind runtime. */
final class NativeMindCallbackScheduler {
    private final MindExecutionPolicy policy;
    private final NativeMindServerBudget serverBudget;
    private final FailureReporter failureReporter;
    private final LongSupplier nanoTime;
    private int serverTick;
    private long entitySpentNanos;
    private int callbackCount;
    private boolean callbackSliceExhausted;
    private boolean tickStarted;

    NativeMindCallbackScheduler(
            MindExecutionPolicy policy,
            NativeMindServerBudget serverBudget,
            FailureReporter failureReporter) {
        this(policy, serverBudget, failureReporter, System::nanoTime);
    }

    NativeMindCallbackScheduler(
            MindExecutionPolicy policy,
            NativeMindServerBudget serverBudget,
            FailureReporter failureReporter,
            LongSupplier nanoTime) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.serverBudget = Objects.requireNonNull(serverBudget, "serverBudget");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    void beginTick(int currentServerTick) {
        serverTick = currentServerTick;
        entitySpentNanos = 0L;
        callbackCount = 0;
        callbackSliceExhausted = false;
        tickStarted = true;
    }

    <T> CallbackResult<T> invoke(String callbackIdentifier, Supplier<T> callback) {
        if (!tickStarted) {
            throw new IllegalStateException("Mind callback scheduler tick has not started");
        }
        Objects.requireNonNull(callbackIdentifier, "callbackIdentifier");
        Objects.requireNonNull(callback, "callback");
        if (callbackSliceExhausted
                || callbackCount >= policy.maxCallbacksPerEntityTick()
                || entitySpentNanos >= policy.maxEntityNanosPerTick()
                || !serverBudget.permits(serverTick, policy.maxServerNanosPerTick())) {
            return CallbackResult.skipped();
        }

        callbackCount++;
        long started = nanoTime.getAsLong();
        try {
            T result = callback.get();
            record(elapsedSince(started));
            return CallbackResult.success(result);
        } catch (MindRunawayException exception) {
            record(elapsedSince(started));
            failureReporter.report(
                    callbackIdentifier,
                    MindFailure.Kind.RUNAWAY_LIMIT_EXCEEDED,
                    exception);
            return CallbackResult.failed();
        } catch (RuntimeException exception) {
            record(elapsedSince(started));
            failureReporter.report(
                    callbackIdentifier,
                    MindFailure.Kind.CALLBACK_EXCEPTION,
                    exception);
            return CallbackResult.failed();
        }
    }

    private void record(long elapsedNanos) {
        long next = entitySpentNanos + elapsedNanos;
        entitySpentNanos = next < entitySpentNanos ? Long.MAX_VALUE : next;
        serverBudget.record(serverTick, elapsedNanos);
        if (elapsedNanos > policy.maxCallbackNanos()) callbackSliceExhausted = true;
    }

    private long elapsedSince(long started) {
        return Math.max(0L, nanoTime.getAsLong() - started);
    }

    @FunctionalInterface
    interface FailureReporter {
        void report(String callbackIdentifier, MindFailure.Kind kind, Throwable cause);
    }

    record CallbackResult<T>(State state, T value) {
        static <T> CallbackResult<T> success(T value) {
            return new CallbackResult<>(State.SUCCESS, value);
        }

        static <T> CallbackResult<T> skipped() {
            return new CallbackResult<>(State.SKIPPED, null);
        }

        static <T> CallbackResult<T> failed() {
            return new CallbackResult<>(State.FAILED, null);
        }

        boolean successful() {
            return state == State.SUCCESS;
        }

        boolean wasSkipped() {
            return state == State.SKIPPED;
        }

        enum State {
            SUCCESS,
            SKIPPED,
            FAILED
        }
    }
}
