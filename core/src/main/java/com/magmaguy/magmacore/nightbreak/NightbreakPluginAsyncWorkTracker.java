package com.magmaguy.magmacore.nightbreak;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntPredicate;

/**
 * Owns the bounded transition from active updater workers to a plugin-lifecycle
 * shutdown. A plugin's shaded classes must remain available until every worker
 * that entered them has exited.
 */
final class NightbreakPluginAsyncWorkTracker {
    private static final long SHUTDOWN_QUIESCENCE_TIMEOUT_SECONDS = 30L;

    private final Map<JavaPlugin, Set<Work>> activeWork = new IdentityHashMap<>();
    private final IntPredicate taskActive;

    NightbreakPluginAsyncWorkTracker() {
        this(taskId -> Bukkit.getScheduler().isQueued(taskId)
                || Bukkit.getScheduler().isCurrentlyRunning(taskId));
    }

    NightbreakPluginAsyncWorkTracker(IntPredicate taskActive) {
        this.taskActive = taskActive;
    }

    synchronized Work register(JavaPlugin plugin) {
        Work work = new Work(plugin);
        activeWork
                .computeIfAbsent(plugin, ignored -> ConcurrentHashMap.newKeySet())
                .add(work);
        return work;
    }

    synchronized List<Work> snapshot(JavaPlugin plugin) {
        Set<Work> currentWork = activeWork.get(plugin);
        return currentWork == null
                ? List.of()
                : new ArrayList<>(currentWork);
    }

    void awaitQuiescence(String pluginName, List<Work> work) {
        if (work.isEmpty()) return;
        if (work.stream().anyMatch(Work::isRunningOnCurrentThread)) {
            throw new IllegalStateException(
                    pluginName + " cannot shut down reentrantly from an updater worker.");
        }

        long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(SHUTDOWN_QUIESCENCE_TIMEOUT_SECONDS);
        CompletableFuture<?>[] dispatchesAndBodies = work.stream()
                .flatMap(item -> java.util.stream.Stream.of(
                        item.dispatchCompleted(), item.completion()))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(dispatchesAndBodies).get(
                    remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
            while (work.stream().anyMatch(this::isTaskActive)) {
                long remainingNanos = remainingNanos(deadlineNanos);
                TimeUnit.NANOSECONDS.sleep(Math.min(
                        remainingNanos,
                        TimeUnit.MILLISECONDS.toNanos(10L)));
            }
            synchronized (this) {
                work.forEach(this::unregisterLocked);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for " + pluginName
                            + " updater work to stop.",
                    exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException(
                    pluginName + " updater termination completed exceptionally.",
                    exception.getCause());
        } catch (TimeoutException exception) {
            throw new IllegalStateException(
                    pluginName + " updater work did not stop within "
                            + SHUTDOWN_QUIESCENCE_TIMEOUT_SECONDS
                            + " seconds; refusing to complete shutdown while shaded code is still running.",
                    exception);
        }
    }

    private boolean isTaskActive(Work work) {
        int taskId = work.taskId();
        return taskId >= 0 && taskActive.test(taskId);
    }

    private static long remainingNanos(long deadlineNanos) throws TimeoutException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            throw new TimeoutException("Updater shutdown deadline elapsed.");
        }
        return remainingNanos;
    }

    private void unregisterLocked(Work work) {
        Set<Work> currentWork = activeWork.get(work.plugin);
        if (currentWork == null) return;
        currentWork.remove(work);
        if (currentWork.isEmpty()) {
            activeWork.remove(work.plugin);
        }
    }

    static final class Work implements AutoCloseable {
        private final JavaPlugin plugin;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final CompletableFuture<Void> dispatchCompleted = new CompletableFuture<>();
        private final CompletableFuture<Void> shutdownRequested = new CompletableFuture<>();
        private BukkitTask task;
        private Thread runningThread;
        private boolean terminal;

        private Work(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        synchronized boolean start() {
            if (terminal || runningThread != null) return false;
            runningThread = Thread.currentThread();
            dispatchCompleted.complete(null);
            return true;
        }

        void attach(BukkitTask task) {
            boolean cancel;
            synchronized (this) {
                this.task = task;
                cancel = terminal || shutdownRequested.isDone();
            }
            dispatchCompleted.complete(null);
            if (cancel) cancelTask(task);
        }

        void dispatchFailed() {
            dispatchCompleted.complete(null);
            close();
        }

        void requestShutdown() {
            BukkitTask taskToCancel;
            boolean complete;
            synchronized (this) {
                shutdownRequested.complete(null);
                taskToCancel = task;
                complete = runningThread == null && !terminal;
                if (complete) terminal = true;
            }
            if (taskToCancel != null) cancelTask(taskToCancel);
            if (complete) publishCompletion();
        }

        CompletableFuture<Void> cancellationRequested() {
            return shutdownRequested;
        }

        CompletableFuture<Void> dispatchCompleted() {
            return dispatchCompleted;
        }

        CompletableFuture<Void> completion() {
            return completion;
        }

        synchronized boolean isRunningOnCurrentThread() {
            return runningThread == Thread.currentThread();
        }

        @Override
        public void close() {
            boolean complete;
            synchronized (this) {
                complete = !terminal;
                terminal = true;
                runningThread = null;
            }
            if (complete) publishCompletion();
        }

        private void publishCompletion() {
            completion.complete(null);
        }

        synchronized int taskId() {
            return task == null ? -1 : task.getTaskId();
        }

        private static void cancelTask(BukkitTask task) {
            try {
                task.cancel();
            } catch (RuntimeException ignored) {
                // The server may already have discarded its scheduler.
            }
        }
    }
}
