package com.magmaguy.magmacore.initialization;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginInitializationLifecycleTest {
    @Test
    void dependencyReadyDispatchCanOnlyBeClaimedOnce() {
        PluginInitializationLifecycle lifecycle = new PluginInitializationLifecycle();
        PluginInitializationLifecycle.Attempt attempt = lifecycle.beginAttempt();

        long claims = IntStream.range(0, 100)
                .parallel()
                .filter(ignored -> attempt.claimAsyncDispatch())
                .count();

        assertEquals(1, claims);
    }

    @Test
    void replacementWaitsForRunningAsyncAttemptToExit() {
        PluginInitializationLifecycle lifecycle = new PluginInitializationLifecycle();
        PluginInitializationLifecycle.Attempt first = lifecycle.beginAttempt();
        assertTrue(first.claimAsyncDispatch());
        assertTrue(first.startAsync());

        PluginInitializationLifecycle.Attempt replacement = lifecycle.beginAttempt();

        assertFalse(lifecycle.isCurrent(first));
        assertTrue(lifecycle.isCurrent(replacement));
        assertFalse(replacement.predecessor().isDone());

        first.completeAsyncWithoutSync();

        assertTrue(replacement.predecessor().isDone());
    }

    @Test
    void thirdAttemptCannotBypassRunningFirstAttempt() {
        PluginInitializationLifecycle lifecycle = new PluginInitializationLifecycle();
        PluginInitializationLifecycle.Attempt first = lifecycle.beginAttempt();
        assertTrue(first.claimAsyncDispatch());
        assertTrue(first.startAsync());

        PluginInitializationLifecycle.Attempt queued = lifecycle.beginAttempt();
        assertTrue(queued.claimAsyncDispatch());
        PluginInitializationLifecycle.Attempt replacement = lifecycle.beginAttempt();
        assertTrue(replacement.claimAsyncDispatch());

        assertFalse(replacement.predecessor().isDone());
        first.completeAsyncWithoutSync();
        assertTrue(replacement.predecessor().isDone());
    }

    @Test
    void replacementInvalidatesQueuedSyncWithoutBlocking() {
        PluginInitializationLifecycle lifecycle = new PluginInitializationLifecycle();
        PluginInitializationLifecycle.Attempt first = lifecycle.beginAttempt();
        assertTrue(first.claimAsyncDispatch());
        assertTrue(first.startAsync());
        assertTrue(first.scheduleSync(() -> {
        }));

        PluginInitializationLifecycle.Attempt replacement = lifecycle.beginAttempt();

        assertTrue(replacement.predecessor().isDone());
        assertFalse(first.startSync());
    }

    @Test
    void replacementWaitsForRunningSyncAttemptToExit() {
        PluginInitializationLifecycle lifecycle = new PluginInitializationLifecycle();
        PluginInitializationLifecycle.Attempt first = lifecycle.beginAttempt();
        assertTrue(first.claimAsyncDispatch());
        assertTrue(first.startAsync());
        assertTrue(first.scheduleSync(() -> {
        }));
        assertTrue(first.startSync());

        PluginInitializationLifecycle.Attempt replacement = lifecycle.beginAttempt();

        assertFalse(replacement.predecessor().isDone());
        first.completeSync();
        assertTrue(replacement.predecessor().isDone());
    }

    @Test
    void rejectedSyncSubmissionCanBeRetriedWithFailurePhase() {
        PluginInitializationLifecycle lifecycle = new PluginInitializationLifecycle();
        PluginInitializationLifecycle.Attempt attempt = lifecycle.beginAttempt();
        assertTrue(attempt.claimAsyncDispatch());
        assertTrue(attempt.startAsync());

        assertThrows(
                IllegalStateException.class,
                () -> attempt.scheduleSync(
                        () -> {
                            throw new IllegalStateException("rejected");
                        }));

        assertFalse(attempt.serializedCompletion().isDone());
        assertTrue(attempt.scheduleSync(() -> {
        }));
        assertTrue(attempt.startSync());
        attempt.completeSync();
        assertTrue(attempt.serializedCompletion().isDone());
    }

    @Test
    void failureCanMoveWaitingAttemptOntoSyncPhase() {
        PluginInitializationLifecycle lifecycle = new PluginInitializationLifecycle();
        PluginInitializationLifecycle.Attempt attempt = lifecycle.beginAttempt();

        assertTrue(attempt.scheduleFailureSync(() -> {
        }));
        assertTrue(attempt.startSync());
        attempt.completeSync();

        assertTrue(attempt.serializedCompletion().isDone());
    }

    @Test
    void rejectedFailureSubmissionCanBeRetried() {
        PluginInitializationLifecycle lifecycle = new PluginInitializationLifecycle();
        PluginInitializationLifecycle.Attempt attempt = lifecycle.beginAttempt();

        assertThrows(
                IllegalStateException.class,
                () -> attempt.scheduleFailureSync(() -> {
                    throw new IllegalStateException("rejected");
                }));

        assertFalse(attempt.serializedCompletion().isDone());
        assertTrue(attempt.scheduleFailureSync(() -> {
        }));
        assertTrue(attempt.startSync());
        attempt.completeSync();
        assertTrue(attempt.serializedCompletion().isDone());
    }

    @Test
    void terminalAttemptNeverInvokesAScheduler() {
        PluginInitializationLifecycle lifecycle = new PluginInitializationLifecycle();
        PluginInitializationLifecycle.Attempt attempt = lifecycle.beginAttempt();
        attempt.completeTerminal();
        AtomicBoolean invoked = new AtomicBoolean();

        assertFalse(attempt.scheduleSync(() -> invoked.set(true)));
        assertFalse(attempt.scheduleFailureSync(() -> invoked.set(true)));
        assertFalse(invoked.get());
    }

    @Test
    void lifecycleNeverRunsCancellationCallbackUnderItsMonitor() {
        PluginInitializationLifecycle lifecycle = new PluginInitializationLifecycle();
        PluginInitializationLifecycle.Attempt attempt = lifecycle.beginAttempt();

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            CompletableFuture<Void> callback =
                    attempt.serializedCompletion().thenRun(() -> {
                    CountDownLatch entered = new CountDownLatch(1);
                    Thread observer = new Thread(() -> {
                        lifecycle.isShutdownRequested();
                        entered.countDown();
                    });
                    observer.start();
                    try {
                        observer.join(1_000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                    assertFalse(observer.isAlive());
                    assertEquals(0, entered.getCount());
                });
            lifecycle.shutdownCurrent();
            callback.join();
        });
    }

    @Test
    void shutdownCancelsCurrentAttemptAndRejectsNewAdmission() {
        PluginInitializationLifecycle lifecycle = new PluginInitializationLifecycle();
        PluginInitializationLifecycle.Attempt attempt = lifecycle.beginAttempt();

        lifecycle.requestShutdown();

        assertTrue(attempt.isCancelled());
        assertTrue(attempt.serializedCompletion().isDone());
        assertTrue(lifecycle.isShutdownRequested());
        assertThrows(IllegalStateException.class, lifecycle::beginAttempt);
    }

    @Test
    void resumedLifecycleStillWaitsForCancelledWorkerToExit() {
        PluginInitializationLifecycle lifecycle = new PluginInitializationLifecycle();
        PluginInitializationLifecycle.Attempt attempt = lifecycle.beginAttempt();
        assertTrue(attempt.claimAsyncDispatch());
        assertTrue(attempt.startAsync());

        lifecycle.requestShutdown();
        lifecycle.resume();
        PluginInitializationLifecycle.Attempt replacement = lifecycle.beginAttempt();

        assertFalse(replacement.predecessor().isDone());
        attempt.completeAsyncWithoutSync();
        assertTrue(replacement.predecessor().isDone());
    }

    @Test
    void replacementFailureFinalizationCanReserveThePredecessorChain() {
        PluginInitializationLifecycle lifecycle = new PluginInitializationLifecycle();
        PluginInitializationLifecycle.Attempt attempt = lifecycle.beginAttempt();
        assertTrue(attempt.claimAsyncDispatch());
        assertTrue(attempt.startAsync());

        PluginInitializationLifecycle.Attempt replacement =
                lifecycle.beginAttempt();
        AtomicBoolean failureFinalized = new AtomicBoolean();
        assertTrue(replacement.claimAsyncDispatch());
        replacement.predecessor().thenRun(
                () -> failureFinalized.set(true));

        assertFalse(failureFinalized.get());
        attempt.completeAsyncWithoutSync();
        assertTrue(failureFinalized.get());
    }

    @Test
    void generationsAreUniqueAndStaleAttemptCannotBecomeCurrentAgain() {
        PluginInitializationLifecycle lifecycle = new PluginInitializationLifecycle();
        PluginInitializationLifecycle.Attempt first = lifecycle.beginAttempt();
        PluginInitializationLifecycle.Attempt second = lifecycle.beginAttempt();

        assertNotEquals(first.generation(), second.generation());
        assertFalse(lifecycle.isCurrent(first));
        assertTrue(lifecycle.isCurrent(second));

        first.cancel();

        assertFalse(lifecycle.isCurrent(first));
        assertTrue(lifecycle.isCurrent(second));
    }
}
