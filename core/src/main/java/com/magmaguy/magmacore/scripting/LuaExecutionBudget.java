package com.magmaguy.magmacore.scripting;

import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.DebugLib;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.TimeUnit;

/**
 * Enforces a hard execution budget inside the Lua VM.
 * <p>
 * Measuring elapsed time after a Lua callback returns cannot protect the server from a script
 * that never returns. LuaJ calls {@link DebugLib#onInstruction(int, Varargs, int)} while executing
 * bytecode, which gives us a cooperative interruption point without running Bukkit-facing scripts
 * on an unsafe worker thread.
 * <p>
 * The time budget uses current-thread CPU time so a valid script is not charged for time when its
 * server thread is descheduled. JVMs without that clock use a deliberately more generous elapsed
 * fallback; the instruction ceiling remains active in either mode, so the fallback does not make
 * non-terminating scripts unbounded.
 */
final class LuaExecutionBudget {

    static final long MAX_CPU_MILLIS = 50L;
    // Five times the CPU allowance avoids a hair-trigger fallback while remaining fail-closed.
    static final long MAX_FALLBACK_ELAPSED_MILLIS = 250L;
    static final long MAX_INSTRUCTIONS = 250_000L;
    private static final long CLOCK_CHECK_MASK = 0x3FFL;
    private static final ExecutionClock ELAPSED_FALLBACK_CLOCK =
            new ElapsedExecutionClock();
    private static final ExecutionClock SYSTEM_CLOCK = createSystemClock();
    private static final ThreadLocal<Budget> ACTIVE_BUDGET = new ThreadLocal<>();

    private LuaExecutionBudget() {
    }

    static <T> T run(BudgetedCall<T> call) {
        return run(call, SYSTEM_CLOCK);
    }

    static <T> T run(BudgetedCall<T> call, ExecutionClock clock) {
        return run(call, clock, MAX_INSTRUCTIONS);
    }

    static <T> T run(
            BudgetedCall<T> call,
            long maxCpuNanos,
            long maxInstructions) {
        if (maxCpuNanos <= 0L || maxInstructions <= 0L) {
            throw new IllegalArgumentException("Lua execution limits must be positive");
        }
        long fallbackNanos = maxCpuNanos > Long.MAX_VALUE / 5L
                ? Long.MAX_VALUE
                : maxCpuNanos * 5L;
        return run(call, new LimitedExecutionClock(
                SYSTEM_CLOCK,
                maxCpuNanos,
                fallbackNanos), maxInstructions);
    }

    private static <T> T run(
            BudgetedCall<T> call,
            ExecutionClock clock,
            long maxInstructions) {
        Budget existing = ACTIVE_BUDGET.get();
        if (existing != null) {
            return call.call();
        }

        Budget budget = new Budget(clock, maxInstructions);
        ACTIVE_BUDGET.set(budget);
        try {
            T result = call.call();
            checkTimeBudget(budget);
            return result;
        } finally {
            ACTIVE_BUDGET.remove();
        }
    }

    static DebugLib debugLibrary() {
        return new BudgetDebugLib();
    }

    private static void onInstruction() {
        Budget budget = ACTIVE_BUDGET.get();
        if (budget == null) return;

        long instructions = ++budget.instructions;
        if (instructions > budget.instructionLimit) {
            throw new RunawayLimitExceeded("Lua instruction budget exceeded ("
                    + budget.instructionLimit + " instruction limit)");
        }
        if ((instructions & CLOCK_CHECK_MASK) == 0L) {
            checkTimeBudget(budget);
        }
    }

    private static void checkTimeBudget(Budget budget) {
        long nowNanos = budget.clock.nanoTime();
        if (nowNanos < 0L) {
            budget.useElapsedFallback();
            nowNanos = budget.clock.nanoTime();
        }

        if (nowNanos - budget.clockStartNanos <= budget.clock.limitNanos()) {
            return;
        }

        if (budget.clock.kind() == ClockKind.THREAD_CPU_TIME) {
            throw new RunawayLimitExceeded("Lua CPU-time budget exceeded ("
                    + formatMillis(budget.clock.limitNanos())
                    + "ms current-thread CPU limit)");
        }
        throw new RunawayLimitExceeded("Lua elapsed-time fallback budget exceeded ("
                + formatMillis(budget.clock.limitNanos())
                + "ms fallback; current-thread CPU time unavailable)");
    }

    private static String formatMillis(long nanos) {
        long wholeMillis = TimeUnit.NANOSECONDS.toMillis(nanos);
        if (TimeUnit.MILLISECONDS.toNanos(wholeMillis) == nanos) {
            return Long.toString(wholeMillis);
        }
        return Double.toString(nanos / 1_000_000.0D);
    }

    private static ExecutionClock createSystemClock() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        try {
            if (threadBean.isCurrentThreadCpuTimeSupported()) {
                if (!threadBean.isThreadCpuTimeEnabled()) {
                    threadBean.setThreadCpuTimeEnabled(true);
                }
                if (threadBean.isThreadCpuTimeEnabled()
                        && threadBean.getCurrentThreadCpuTime() >= 0L) {
                    return new ThreadCpuExecutionClock(threadBean);
                }
            }
        } catch (SecurityException | UnsupportedOperationException ignored) {
            // The elapsed clock below remains bounded by time and by the instruction ceiling.
        }
        return ELAPSED_FALLBACK_CLOCK;
    }

    @FunctionalInterface
    interface BudgetedCall<T> {
        T call();
    }

    enum ClockKind {
        THREAD_CPU_TIME,
        ELAPSED_TIME_FALLBACK
    }

    interface ExecutionClock {
        long nanoTime();

        long limitNanos();

        ClockKind kind();
    }

    static final class RunawayLimitExceeded extends LuaError {
        private RunawayLimitExceeded(String message) {
            super(message);
        }
    }

    private static final class Budget {
        private ExecutionClock clock;
        private final ExecutionClock fallbackClock;
        private long clockStartNanos;
        private final long elapsedStartNanos;
        private final long instructionLimit;
        private long instructions;

        private Budget(ExecutionClock requestedClock, long instructionLimit) {
            elapsedStartNanos = System.nanoTime();
            clock = requestedClock;
            fallbackClock = requestedClock instanceof LimitedExecutionClock limited
                    ? new FixedElapsedExecutionClock(limited.elapsedLimitNanos)
                    : ELAPSED_FALLBACK_CLOCK;
            this.instructionLimit = instructionLimit;
            clockStartNanos = clock.nanoTime();
            if (clockStartNanos < 0L) {
                useElapsedFallback();
            }
        }

        private void useElapsedFallback() {
            clock = fallbackClock;
            clockStartNanos = elapsedStartNanos;
        }
    }

    private static final class ThreadCpuExecutionClock implements ExecutionClock {
        private final ThreadMXBean threadBean;

        private ThreadCpuExecutionClock(ThreadMXBean threadBean) {
            this.threadBean = threadBean;
        }

        @Override
        public long nanoTime() {
            return threadBean.getCurrentThreadCpuTime();
        }

        @Override
        public long limitNanos() {
            return TimeUnit.MILLISECONDS.toNanos(MAX_CPU_MILLIS);
        }

        @Override
        public ClockKind kind() {
            return ClockKind.THREAD_CPU_TIME;
        }
    }

    private static final class ElapsedExecutionClock implements ExecutionClock {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long limitNanos() {
            return TimeUnit.MILLISECONDS.toNanos(MAX_FALLBACK_ELAPSED_MILLIS);
        }

        @Override
        public ClockKind kind() {
            return ClockKind.ELAPSED_TIME_FALLBACK;
        }
    }

    private static final class LimitedExecutionClock implements ExecutionClock {
        private final ExecutionClock delegate;
        private final long cpuLimitNanos;
        private final long elapsedLimitNanos;

        private LimitedExecutionClock(
                ExecutionClock delegate,
                long cpuLimitNanos,
                long elapsedLimitNanos) {
            this.delegate = delegate;
            this.cpuLimitNanos = cpuLimitNanos;
            this.elapsedLimitNanos = elapsedLimitNanos;
        }

        @Override
        public long nanoTime() {
            return delegate.nanoTime();
        }

        @Override
        public long limitNanos() {
            return kind() == ClockKind.THREAD_CPU_TIME
                    ? cpuLimitNanos
                    : elapsedLimitNanos;
        }

        @Override
        public ClockKind kind() {
            return delegate.kind();
        }
    }

    private static final class FixedElapsedExecutionClock implements ExecutionClock {
        private final long limitNanos;

        private FixedElapsedExecutionClock(long limitNanos) {
            this.limitNanos = limitNanos;
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long limitNanos() {
            return limitNanos;
        }

        @Override
        public ClockKind kind() {
            return ClockKind.ELAPSED_TIME_FALLBACK;
        }
    }

    /**
     * The debug table itself is removed from the sandbox after this library is installed. These
     * callbacks exist only so the VM can enforce the host-owned budget.
     */
    private static final class BudgetDebugLib extends DebugLib {
        @Override
        public void onInstruction(int pc, Varargs v, int top) {
            LuaExecutionBudget.onInstruction();
        }

        @Override
        public void onCall(LuaFunction function) {
            // Call-stack bookkeeping is unnecessary for the execution budget.
        }

        @Override
        public void onCall(LuaClosure function, Varargs varargs, LuaValue[] stack) {
            // Call-stack bookkeeping is unnecessary for the execution budget.
        }

        @Override
        public void onReturn() {
            // Call-stack bookkeeping is unnecessary for the execution budget.
        }
    }
}
