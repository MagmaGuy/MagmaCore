package com.magmaguy.magmacore.dlc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationImporterWorldUnloadTest {

    @Test
    void shutdownCancelsAParkedMainThreadUnloadAndReleasesTheWaiter()
            throws Exception {
        AtomicBoolean unloadRan = new AtomicBoolean();
        FutureTask<Boolean> parkedUnload = new FutureTask<>(() -> {
            unloadRan.set(true);
            return true;
        });
        AtomicBoolean shutdownRequested = new AtomicBoolean();
        CountDownLatch shutdownWasPolled = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Throwable> waitingImporter = executor.submit(() -> {
                try {
                    ConfigurationImporter.awaitWorldUnload(
                            parkedUnload,
                            () -> {
                                boolean requested =
                                        shutdownRequested.get();
                                shutdownWasPolled.countDown();
                                return requested;
                            },
                            "acceptance_world");
                    return null;
                } catch (Throwable throwable) {
                    return throwable;
                }
            });

            assertTrue(shutdownWasPolled.await(1, TimeUnit.SECONDS));
            shutdownRequested.set(true);

            Throwable failure = waitingImporter.get(1, TimeUnit.SECONDS);
            assertInstanceOf(InterruptedIOException.class, failure);
            assertTrue(parkedUnload.isCancelled());
            assertFalse(unloadRan.get());
        }
    }

    @Test
    void completedUnloadResultIsPreserved() throws Exception {
        assertTrue(ConfigurationImporter.awaitWorldUnload(
                CompletableFuture.completedFuture(true),
                () -> false,
                "loaded_world"));
        assertFalse(ConfigurationImporter.awaitWorldUnload(
                CompletableFuture.completedFuture(false),
                () -> false,
                "stubborn_world"));
    }

    @Test
    void mainThreadUnloadFailureRetainsItsCause() {
        IllegalStateException cause =
                new IllegalStateException("server rejected unload");
        CompletableFuture<Boolean> failed = new CompletableFuture<>();
        failed.completeExceptionally(cause);

        IOException failure;
        try {
            ConfigurationImporter.awaitWorldUnload(
                    failed,
                    () -> false,
                    "broken_world");
            throw new AssertionError("Expected world unload to fail.");
        } catch (IOException exception) {
            failure = exception;
        }

        assertSame(cause, failure.getCause());
    }
}
