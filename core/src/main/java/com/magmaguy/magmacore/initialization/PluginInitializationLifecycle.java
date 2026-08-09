package com.magmaguy.magmacore.initialization;

import java.util.concurrent.CompletableFuture;
import java.util.UUID;

/**
 * Serializes initialization attempts for one plugin and invalidates stale
 * callbacks when a reload starts a replacement attempt.
 */
final class PluginInitializationLifecycle {
    private long nextGeneration;
    private Attempt current;
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    private boolean shutdownRequested;

    Attempt beginAttempt() {
        AttemptStart start = prepareAttempt();
        if (start == null) {
            throw new IllegalStateException(
                    "Cannot start an initialization attempt after shutdown was requested.");
        }
        start.cancelPrevious();
        return start.attempt();
    }

    synchronized AttemptStart prepareAttempt() {
        if (shutdownRequested) {
            return null;
        }
        Attempt previous;
        Attempt attempt;
        previous = current;
        attempt = new Attempt(++nextGeneration, tail);
        current = attempt;
        tail = attempt.serializedCompletion();
        return new AttemptStart(attempt, previous);
    }

    Attempt cancelCurrent() {
        Attempt attempt;
        boolean publishCompletion;
        synchronized (this) {
            attempt = current;
            publishCompletion = attempt != null
                    && attempt.markCancelled();
        }
        if (attempt != null) {
            attempt.publishCompletion(publishCompletion);
        }
        return attempt;
    }

    Attempt requestShutdown() {
        Attempt attempt;
        boolean publishCompletion;
        synchronized (this) {
            shutdownRequested = true;
            attempt = current;
            publishCompletion = attempt != null
                    && attempt.markCancelled();
        }
        if (attempt != null) {
            attempt.publishCompletion(publishCompletion);
        }
        return attempt;
    }

    Attempt shutdownCurrent() {
        Attempt attempt;
        boolean publishCompletion;
        synchronized (this) {
            shutdownRequested = true;
            attempt = current;
            publishCompletion = attempt != null
                    && attempt.markCancelled();
        }
        if (attempt == null) {
            return null;
        }

        attempt.publishCompletion(publishCompletion);
        return attempt;
    }

    synchronized boolean isCurrent(Attempt attempt) {
        return current == attempt && !attempt.isCancelled();
    }

    synchronized boolean isShutdownRequested() {
        return shutdownRequested
                || current != null && current.isCancelled();
    }

    synchronized void resume() {
        shutdownRequested = false;
    }

    record AttemptStart(Attempt attempt, Attempt previous) {
        void cancelPrevious() {
            if (previous != null) {
                previous.cancel();
            }
        }
    }

    static final class Attempt {
        private enum Phase {
            WAITING,
            ASYNC_SCHEDULED,
            ASYNC_RUNNING,
            SYNC_SCHEDULED,
            SYNC_RUNNING,
            TERMINAL
        }

        private final long generation;
        private final String ownerToken = UUID.randomUUID().toString();
        private final CompletableFuture<Void> predecessor;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final CompletableFuture<Void> serializedCompletion;
        private Phase phase = Phase.WAITING;
        private boolean cancelled;

        private Attempt(long generation, CompletableFuture<Void> predecessor) {
            this.generation = generation;
            this.predecessor = predecessor;
            this.serializedCompletion = predecessor.thenCompose(
                    ignored -> completion);
        }

        long generation() {
            return generation;
        }

        String ownerToken() {
            return ownerToken;
        }

        CompletableFuture<Void> predecessor() {
            return predecessor;
        }

        CompletableFuture<Void> serializedCompletion() {
            return serializedCompletion;
        }

        boolean claimAsyncDispatch() {
            synchronized (this) {
                if (cancelled || phase != Phase.WAITING) {
                    return false;
                }

                phase = Phase.ASYNC_SCHEDULED;
                return true;
            }
        }

        boolean startAsync() {
            boolean publishCompletion = false;
            synchronized (this) {
                if (cancelled || phase != Phase.ASYNC_SCHEDULED) {
                    publishCompletion = markTerminal();
                } else {
                    phase = Phase.ASYNC_RUNNING;
                    return true;
                }
            }

            publishCompletion(publishCompletion);
            return false;
        }

        boolean scheduleSync(Runnable scheduler) {
            return scheduleSyncFrom(Phase.ASYNC_RUNNING, scheduler);
        }

        boolean scheduleFailureSync(Runnable scheduler) {
            Phase previousPhase = null;
            boolean publishCompletion = false;
            boolean rejected = false;
            synchronized (this) {
                if (cancelled
                        || phase == Phase.SYNC_SCHEDULED
                        || phase == Phase.SYNC_RUNNING
                        || phase == Phase.TERMINAL) {
                    publishCompletion = markTerminal();
                    rejected = true;
                } else {
                    previousPhase = phase;
                    phase = Phase.SYNC_SCHEDULED;
                }
            }
            publishCompletion(publishCompletion);
            if (rejected) {
                return false;
            }

            try {
                scheduler.run();
                return true;
            } catch (Throwable throwable) {
                synchronized (this) {
                    if (phase == Phase.SYNC_SCHEDULED) {
                        phase = previousPhase;
                    }
                }
                throw throwable;
            }
        }

        private boolean scheduleSyncFrom(Phase requiredPhase, Runnable scheduler) {
            boolean publishCompletion = false;
            boolean rejected = false;
            synchronized (this) {
                if (cancelled || phase != requiredPhase) {
                    publishCompletion = markTerminal();
                    rejected = true;
                } else {
                    phase = Phase.SYNC_SCHEDULED;
                }
            }
            publishCompletion(publishCompletion);
            if (rejected) {
                return false;
            }

            try {
                scheduler.run();
                return true;
            } catch (Throwable throwable) {
                synchronized (this) {
                    if (phase == Phase.SYNC_SCHEDULED) {
                        phase = requiredPhase;
                    }
                }
                throw throwable;
            }
        }

        boolean startSync() {
            boolean publishCompletion = false;
            synchronized (this) {
                if (cancelled || phase != Phase.SYNC_SCHEDULED) {
                    publishCompletion = markTerminal();
                } else {
                    phase = Phase.SYNC_RUNNING;
                    return true;
                }
            }

            publishCompletion(publishCompletion);
            return false;
        }

        void completeAsyncWithoutSync() {
            boolean publishCompletion = false;
            synchronized (this) {
                if (phase == Phase.ASYNC_RUNNING) {
                    publishCompletion = markTerminal();
                }
            }
            publishCompletion(publishCompletion);
        }

        void completeSync() {
            boolean publishCompletion = false;
            synchronized (this) {
                if (phase == Phase.SYNC_RUNNING) {
                    publishCompletion = markTerminal();
                }
            }
            publishCompletion(publishCompletion);
        }

        void completeTerminal() {
            boolean publishCompletion;
            synchronized (this) {
                publishCompletion = markTerminal();
            }
            publishCompletion(publishCompletion);
        }

        void cancel() {
            boolean publishCompletion = markCancelled();
            publishCompletion(publishCompletion);
        }

        synchronized boolean isCancelled() {
            return cancelled;
        }

        private boolean markTerminal() {
            if (phase == Phase.TERMINAL) {
                return false;
            }
            phase = Phase.TERMINAL;
            return true;
        }

        private synchronized boolean markCancelled() {
            cancelled = true;
            return phase != Phase.ASYNC_RUNNING
                    && phase != Phase.SYNC_RUNNING
                    && markTerminal();
        }

        private void publishCompletion(boolean shouldPublish) {
            if (shouldPublish) {
                completion.complete(null);
            }
        }
    }
}
