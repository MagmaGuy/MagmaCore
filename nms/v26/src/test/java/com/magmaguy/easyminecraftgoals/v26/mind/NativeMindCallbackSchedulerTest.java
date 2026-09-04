package com.magmaguy.easyminecraftgoals.v26.mind;

import com.magmaguy.magmacore.ai.MindExecutionPolicy;
import com.magmaguy.magmacore.ai.MindFailure;
import com.magmaguy.magmacore.ai.MindRunawayPolicy;
import com.magmaguy.magmacore.ai.MindRunawayException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeMindCallbackSchedulerTest {
    @Test
    void completedCallbackCrossingSoftLimitSucceedsThenBackpressuresLaterWork() {
        List<MindFailure.Kind> failures = new ArrayList<>();
        NativeMindCallbackScheduler scheduler = new NativeMindCallbackScheduler(
                new MindExecutionPolicy(
                        5L,
                        20L,
                        40L,
                        4,
                        8,
                        MindRunawayPolicy.DEFAULT),
                new NativeMindServerBudget(),
                (callback, kind, cause) -> failures.add(kind),
                clock(100L, 106L));
        scheduler.beginTick(10);

        NativeMindCallbackScheduler.CallbackResult<String> completed =
                scheduler.invoke("fixture:slow", () -> "completed");
        NativeMindCallbackScheduler.CallbackResult<String> deferred =
                scheduler.invoke("fixture:later", () -> "must not run");

        assertTrue(completed.successful());
        assertEquals("completed", completed.value());
        assertTrue(deferred.wasSkipped());
        assertTrue(failures.isEmpty());
    }

    @Test
    void entityBudgetExhaustionDefersWithoutReportingFailure() {
        List<MindFailure.Kind> failures = new ArrayList<>();
        AtomicInteger executions = new AtomicInteger();
        NativeMindCallbackScheduler scheduler = new NativeMindCallbackScheduler(
                policy(5L, 5L, 10L),
                new NativeMindServerBudget(),
                (callback, kind, cause) -> failures.add(kind),
                clock(0L, 5L));
        scheduler.beginTick(20);

        scheduler.invoke("fixture:first", () -> executions.incrementAndGet());
        NativeMindCallbackScheduler.CallbackResult<Integer> deferred =
                scheduler.invoke("fixture:later", () -> executions.incrementAndGet());

        assertTrue(deferred.wasSkipped());
        assertEquals(1, executions.get());
        assertTrue(failures.isEmpty());
    }

    @Test
    void sharedServerBudgetExhaustionDefersNextActorWithoutReportingFailure() {
        NativeMindServerBudget serverBudget = new NativeMindServerBudget();
        List<MindFailure.Kind> failures = new ArrayList<>();
        NativeMindCallbackScheduler first = new NativeMindCallbackScheduler(
                policy(5L, 5L, 5L),
                serverBudget,
                (callback, kind, cause) -> failures.add(kind),
                clock(0L, 5L));
        NativeMindCallbackScheduler second = new NativeMindCallbackScheduler(
                policy(5L, 5L, 5L),
                serverBudget,
                (callback, kind, cause) -> failures.add(kind),
                clock(10L, 15L));
        first.beginTick(30);
        second.beginTick(30);
        AtomicBoolean secondExecuted = new AtomicBoolean();

        assertTrue(first.invoke("fixture:first", () -> "done").successful());
        NativeMindCallbackScheduler.CallbackResult<String> deferred =
                second.invoke("fixture:second", () -> {
                    secondExecuted.set(true);
                    return "must not run";
                });

        assertTrue(deferred.wasSkipped());
        assertFalse(secondExecuted.get());
        assertTrue(failures.isEmpty());
    }

    @Test
    void hardRunawayInterruptionReportsItsOwnFailureKind() {
        List<MindFailure.Kind> failures = new ArrayList<>();
        NativeMindCallbackScheduler scheduler = new NativeMindCallbackScheduler(
                policy(5L, 10L, 20L),
                new NativeMindServerBudget(),
                (callback, kind, cause) -> failures.add(kind),
                clock(0L, 1L));
        scheduler.beginTick(40);

        NativeMindCallbackScheduler.CallbackResult<Void> result = scheduler.invoke(
                "fixture:runaway",
                () -> {
                    throw new MindRunawayException("hard limit", null);
                });

        assertFalse(result.successful());
        assertFalse(result.wasSkipped());
        assertEquals(List.of(MindFailure.Kind.RUNAWAY_LIMIT_EXCEEDED), failures);
    }

    private static MindExecutionPolicy policy(
            long callbackNanos,
            long entityNanos,
            long serverNanos) {
        return new MindExecutionPolicy(
                callbackNanos,
                entityNanos,
                serverNanos,
                4,
                8,
                MindRunawayPolicy.DEFAULT);
    }

    private static LongSupplier clock(long... readings) {
        AtomicInteger index = new AtomicInteger();
        return () -> readings[Math.min(index.getAndIncrement(), readings.length - 1)];
    }
}
