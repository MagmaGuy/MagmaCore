package com.magmaguy.magmacore.scripting;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaExecutionBudgetTest {

    private static final ScriptProvider PROVIDER = new ScriptProvider() {
        @Override
        public String getNamespace() {
            return "budget-test";
        }

        @Override
        public java.nio.file.Path getScriptDirectory() {
            return java.nio.file.Path.of(".");
        }

        @Override
        public ScriptHook resolveHook(String key) {
            return "on_spawn".equals(key) ? new ScriptHook(key) : null;
        }
    };

    @Test
    void nonTerminatingTopLevelScriptIsInterruptedInsideTheVm() {
        LuaError error = assertTimeout(
                Duration.ofSeconds(2),
                () -> assertThrows(
                        LuaError.class,
                        () -> ScriptDefinition.validate(
                                "infinite.lua",
                                new File("infinite.lua"),
                                "while true do end",
                                PROVIDER)));

        assertTrue(error.getMessage().contains("budget exceeded"));
    }

    @Test
    void nonTerminatingHookIsInterruptedInsideTheVm() {
        Globals globals = LuaEnvironmentFactory.createGlobals();
        LuaValue callback = globals.load("return function() while true do end end").call();

        LuaError error = assertTimeout(
                Duration.ofSeconds(2),
                () -> assertThrows(
                        LuaError.class,
                        () -> LuaExecutionBudget.run(
                                callback::call,
                                clock(LuaExecutionBudget.ClockKind.THREAD_CPU_TIME, () -> 0L))));

        assertTrue(error.getMessage().contains("instruction budget exceeded"));
    }

    @Test
    void finiteScriptSurvivesWallClockPreemption() {
        Globals globals = LuaEnvironmentFactory.createGlobals();
        LuaValue callback = globals.load("""
                return function()
                    local total = 0
                    for _, radius in ipairs({ 6, 5, 4, 3 }) do
                        local shell = {}
                        local interior = {}
                        for dx = -radius, radius do
                            for dy = 0, radius do
                                for dz = -radius, radius do
                                    local dist = math.sqrt(dx * dx + dy * dy + dz * dz)
                                    if dist <= radius and dist >= radius - 1 then
                                        table.insert(shell, { dx = dx, dy = dy, dz = dz })
                                    elseif dist < radius - 1 then
                                        table.insert(interior, { dx = dx, dy = dy, dz = dz })
                                    end
                                end
                            end
                        end
                        total = total + #shell + #interior
                    end
                    return total
                end
                """).call();

        LuaValue result = assertTimeout(Duration.ofSeconds(2), () ->
                LuaExecutionBudget.run(() -> {
                    try {
                        Thread.sleep(LuaExecutionBudget.MAX_CPU_MILLIS * 3);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                    return callback.call();
                }, clock(LuaExecutionBudget.ClockKind.THREAD_CPU_TIME, () -> 0L)));

        assertEquals(1046, result.checkint());
    }

    @Test
    void cpuTimeFailureIdentifiesTheBudgetThatFired() {
        LuaError error = runWithAdvancingClock(
                LuaExecutionBudget.ClockKind.THREAD_CPU_TIME,
                LuaExecutionBudget.MAX_CPU_MILLIS + 1L);

        assertTrue(error.getMessage().contains("CPU-time budget exceeded"));
        assertTrue(error.getMessage().contains("50ms current-thread CPU limit"));
    }

    @Test
    void elapsedFallbackFailureIdentifiesTheBudgetThatFired() {
        LuaError error = runWithAdvancingClock(
                LuaExecutionBudget.ClockKind.ELAPSED_TIME_FALLBACK,
                LuaExecutionBudget.MAX_FALLBACK_ELAPSED_MILLIS + 1L);

        assertTrue(error.getMessage().contains("elapsed-time fallback budget exceeded"));
        assertTrue(error.getMessage().contains("current-thread CPU time unavailable"));
    }

    private static LuaError runWithAdvancingClock(
            LuaExecutionBudget.ClockKind kind,
            long elapsedMillis) {
        Globals globals = LuaEnvironmentFactory.createGlobals();
        LuaValue callback = globals.load(
                "return function() local total = 0; for i = 1, 10000 do total = total + i end; return total end"
        ).call();
        AtomicInteger reads = new AtomicInteger();

        return assertThrows(
                LuaError.class,
                () -> LuaExecutionBudget.run(
                        callback::call,
                        clock(kind, () -> reads.getAndIncrement() == 0
                                ? 0L
                                : TimeUnit.MILLISECONDS.toNanos(elapsedMillis))));
    }

    private static LuaExecutionBudget.ExecutionClock clock(
            LuaExecutionBudget.ClockKind kind,
            LongSupplier nanoTime) {
        return new LuaExecutionBudget.ExecutionClock() {
            @Override
            public long nanoTime() {
                return nanoTime.getAsLong();
            }

            @Override
            public long limitNanos() {
                long millis = kind == LuaExecutionBudget.ClockKind.THREAD_CPU_TIME
                        ? LuaExecutionBudget.MAX_CPU_MILLIS
                        : LuaExecutionBudget.MAX_FALLBACK_ELAPSED_MILLIS;
                return TimeUnit.MILLISECONDS.toNanos(millis);
            }

            @Override
            public LuaExecutionBudget.ClockKind kind() {
                return kind;
            }
        };
    }
}
