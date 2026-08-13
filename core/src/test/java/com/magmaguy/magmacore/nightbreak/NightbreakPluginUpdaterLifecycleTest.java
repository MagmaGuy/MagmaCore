package com.magmaguy.magmacore.nightbreak;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NightbreakPluginUpdaterLifecycleTest {
    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("FreeMinecraftModels");
    }

    @Test
    void shutdownWaitsForAnAlreadyRunningUpdaterWorker() throws Exception {
        long generation = NightbreakPluginUpdater.lifecycleGeneration(plugin);
        NightbreakPluginAsyncWorkTracker.Work work =
                NightbreakPluginUpdater.registerAsyncWork(plugin, generation);
        assertNotNull(work);
        assertTrue(work.start());

        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(
                () -> NightbreakPluginUpdater.shutdown(plugin));
        work.cancellationRequested().get(5, TimeUnit.SECONDS);

        assertFalse(shutdown.isDone(),
                "shutdown must retain the plugin classloader until a running worker exits");
        assertNull(NightbreakPluginUpdater.registerAsyncWork(plugin, generation),
                "the invalidated generation must reject replacement work");

        work.close();
        shutdown.get(5, TimeUnit.SECONDS);
        assertTrue(work.completion().isDone());
    }

    @Test
    void shutdownCancelsQueuedWorkWithoutWaitingForItsRunnable() {
        NightbreakPluginAsyncWorkTracker tracker =
                new NightbreakPluginAsyncWorkTracker(ignored -> false);
        NightbreakPluginAsyncWorkTracker.Work work = tracker.register(plugin);
        BukkitTask task = mock(BukkitTask.class);
        when(task.getTaskId()).thenReturn(123);
        work.attach(task);
        var snapshot = tracker.snapshot(plugin);

        snapshot.forEach(NightbreakPluginAsyncWorkTracker.Work::requestShutdown);
        tracker.awaitQuiescence(plugin.getName(), snapshot);

        verify(task).cancel();
        assertTrue(work.cancellationRequested().isDone());
        assertTrue(work.completion().isDone());
        assertFalse(work.start(),
                "a runnable dispatched after shutdown must not enter shaded updater code");
        assertTrue(tracker.snapshot(plugin).isEmpty());
    }

    @Test
    void quiescenceWaitsUntilTheSchedulerHasLeftTheRunnable() throws Exception {
        AtomicBoolean taskActive = new AtomicBoolean(true);
        CountDownLatch taskStateObserved = new CountDownLatch(1);
        NightbreakPluginAsyncWorkTracker tracker =
                new NightbreakPluginAsyncWorkTracker(ignored -> {
                    taskStateObserved.countDown();
                    return taskActive.get();
                });
        NightbreakPluginAsyncWorkTracker.Work work = tracker.register(plugin);
        BukkitTask task = mock(BukkitTask.class);
        when(task.getTaskId()).thenReturn(456);
        work.attach(task);
        assertTrue(work.start());
        work.close();

        var snapshot = tracker.snapshot(plugin);
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(
                () -> tracker.awaitQuiescence(plugin.getName(), snapshot));
        assertTrue(taskStateObserved.await(5, TimeUnit.SECONDS));
        assertFalse(shutdown.isDone(),
                "body completion is not enough while Bukkit still reports the task running");

        taskActive.set(false);
        shutdown.get(5, TimeUnit.SECONDS);
        assertTrue(tracker.snapshot(plugin).isEmpty());
    }
}
