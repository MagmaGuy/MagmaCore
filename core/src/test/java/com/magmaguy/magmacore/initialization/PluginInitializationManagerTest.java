package com.magmaguy.magmacore.initialization;

import com.magmaguy.magmacore.MagmaCore;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginInitializationManagerTest {
    @Test
    void successfulAttemptPublishesOnlyAfterEveryCallback() {
        try (Harness harness = new Harness(true)) {
            AtomicInteger sequence = new AtomicInteger();

            PluginInitializationManager.run(
                    harness.plugin,
                    harness.config(List.of()),
                    ignored -> assertEquals(0, sequence.getAndIncrement()),
                    ignored -> assertEquals(1, sequence.getAndIncrement()),
                    () -> assertEquals(2, sequence.getAndIncrement()),
                    ignored -> sequence.set(-100));

            assertEquals(3, sequence.get());
            assertEquals(
                    PluginInitializationState.INITIALIZED,
                    PluginInitializationManager.getState(harness.pluginName));
            verify(harness.bossBar).removeAll();
        }
    }

    @Test
    void replacementInvalidatesQueuedAsyncDispatch() {
        try (Harness harness = new Harness(false)) {
            AtomicInteger asyncRuns = new AtomicInteger();
            AtomicInteger successes = new AtomicInteger();

            harness.runBasic(asyncRuns::incrementAndGet, successes::incrementAndGet);
            harness.runBasic(asyncRuns::incrementAndGet, successes::incrementAndGet);

            assertEquals(2, harness.asyncTasks.size());
            harness.asyncTasks.remove().run();
            harness.asyncTasks.remove().run();

            assertEquals(1, asyncRuns.get());
            assertEquals(1, successes.get());
            assertEquals(
                    PluginInitializationState.INITIALIZED,
                    PluginInitializationManager.getState(harness.pluginName));
        }
    }

    @Test
    void setupFailureRunsFailureCallbackAndPublishesTerminalState() {
        try (Harness harness = new Harness(true)) {
            AtomicInteger failures = new AtomicInteger();

            PluginInitializationManager.run(
                    harness.plugin,
                    null,
                    ignored -> {
                    },
                    ignored -> {
                    },
                    () -> {
                    },
                    ignored -> failures.incrementAndGet());

            assertEquals(1, failures.get());
            assertEquals(
                    PluginInitializationState.FAILED,
                    PluginInitializationManager.getState(harness.pluginName));
        }
    }

    @Test
    void requestShutdownClearsClaimedStateAndLateProgress() {
        try (Harness harness = new Harness(false)) {
            harness.runBasic(() -> {
            }, () -> {
            });

            assertEquals(
                    PluginInitializationState.INITIALIZING,
                    PluginInitializationManager.getState(harness.pluginName));
            PluginInitializationManager.requestShutdown(harness.plugin);

            assertEquals(
                    PluginInitializationState.UNINITIALIZED,
                    PluginInitializationManager.getState(harness.pluginName));
            verify(harness.bossBar).removeAll();
            harness.asyncTasks.remove().run();
            assertEquals(
                    PluginInitializationState.UNINITIALIZED,
                    PluginInitializationManager.getState(harness.pluginName));
        }
    }

    @Test
    void shutdownWaitsForRunningAsyncCallbackToExit() throws Exception {
        try (Harness harness = new Harness(false)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            harness.runBasic(
                    () -> {
                        entered.countDown();
                        try {
                            assertTrue(release.await(2, TimeUnit.SECONDS));
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(exception);
                        }
                    },
                    () -> {
                    });
            Runnable async = harness.asyncTasks.remove();
            CompletableFuture<Void> worker = CompletableFuture.runAsync(async);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            PluginInitializationManager.requestShutdown(harness.plugin);

            CompletableFuture.delayedExecutor(
                            150,
                            TimeUnit.MILLISECONDS)
                    .execute(release::countDown);
            long started = System.nanoTime();
            PluginInitializationManager.shutdown(harness.plugin);
            long elapsedMillis =
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMillis >= 100);
            worker.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void dependencyPollingUsesTheServerThreadScheduler() {
        try (Harness harness = new Harness(false)) {
            Plugin dependency = mock(Plugin.class);
            when(dependency.isEnabled()).thenReturn(true);
            when(harness.pluginManager.getPlugin("Dependency"))
                    .thenReturn(dependency);

            harness.runBasic(
                    harness.config(List.of("Dependency")),
                    () -> {
                    },
                    () -> {
                    });

            verify(harness.scheduler).runTaskTimer(
                    eq(harness.plugin),
                    any(Runnable.class),
                    anyLong(),
                    anyLong());
            verify(harness.scheduler, never()).runTaskTimerAsynchronously(
                    eq(harness.plugin),
                    any(Runnable.class),
                    anyLong(),
                    anyLong());
        }
    }

    @Test
    void repeatedShutdownAndManualOverrideRemainBounded() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try (Harness harness = new Harness(false)) {
                harness.runBasic(() -> {
                }, () -> {
                });
                PluginInitializationManager.setState(
                        harness.plugin,
                        PluginInitializationState.FAILED);
                PluginInitializationManager.shutdown(harness.plugin);
                PluginInitializationManager.shutdown(harness.plugin);
            }
        });
    }

    private static final class Harness implements AutoCloseable {
        private final String pluginName = "InitializationTest-" + UUID.randomUUID();
        private final JavaPlugin plugin = mock(JavaPlugin.class);
        private final PluginManager pluginManager = mock(PluginManager.class);
        private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        private final BukkitTask task = mock(BukkitTask.class);
        private final BossBar bossBar = mock(BossBar.class);
        private final MagmaCore magmaCoreInstance = mock(MagmaCore.class);
        private final Queue<Runnable> asyncTasks = new ArrayDeque<>();
        private final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        private final MockedStatic<HandlerList> handlerList =
                mockStatic(HandlerList.class);
        private final MockedStatic<MagmaCore> magmaCore =
                mockStatic(MagmaCore.class);

        private Harness(boolean executeAsyncImmediately) {
            when(plugin.getName()).thenReturn(pluginName);
            when(plugin.isEnabled()).thenReturn(true);
            when(magmaCoreInstance.getRequestingPlugin()).thenReturn(plugin);
            magmaCore.when(MagmaCore::getInstance)
                    .thenReturn(magmaCoreInstance);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            bukkit.when(Bukkit::getLogger)
                    .thenReturn(Logger.getLogger(pluginName));
            bukkit.when(() -> Bukkit.createBossBar(
                            anyString(),
                            any(BarColor.class),
                            any(BarStyle.class)))
                    .thenReturn(bossBar);
            when(scheduler.runTaskAsynchronously(
                    eq(plugin),
                    any(Runnable.class))).thenAnswer(invocation -> {
                Runnable runnable = invocation.getArgument(1);
                if (executeAsyncImmediately) {
                    runnable.run();
                } else {
                    asyncTasks.add(runnable);
                }
                return task;
            });
            when(scheduler.runTask(
                    eq(plugin),
                    any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(1).run();
                return task;
            });
            when(scheduler.runTaskTimer(
                    eq(plugin),
                    any(Runnable.class),
                    anyLong(),
                    anyLong())).thenReturn(task);
        }

        private PluginInitializationConfig config(List<String> dependencies) {
            return new PluginInitializationConfig(
                    "Test",
                    "test.admin",
                    2,
                    dependencies);
        }

        private void runBasic(Runnable async, Runnable success) {
            runBasic(config(List.of()), async, success);
        }

        private void runBasic(
                PluginInitializationConfig initializationConfig,
                Runnable async,
                Runnable success) {
            PluginInitializationManager.run(
                    plugin,
                    initializationConfig,
                    ignored -> async.run(),
                    ignored -> {
                    },
                    success,
                    ignored -> {
                    });
        }

        @Override
        public void close() {
            try {
                PluginInitializationManager.shutdown(plugin);
            } finally {
                magmaCore.close();
                handlerList.close();
                bukkit.close();
            }
        }
    }
}
