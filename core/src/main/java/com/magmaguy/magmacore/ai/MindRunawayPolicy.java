package com.magmaguy.magmacore.ai;

/**
 * Hard interruption limits for one script callback.
 *
 * <p>These limits protect the server from code that does not return. They are deliberately
 * independent of {@link MindExecutionPolicy}'s soft scheduling budgets, which only defer later
 * work after a callback has completed.</p>
 */
public record MindRunawayPolicy(
        long maxCpuNanosPerCallback,
        long maxInstructionsPerCallback) {

    public static final MindRunawayPolicy DEFAULT = new MindRunawayPolicy(
            50_000_000L,
            50_000L);

    public MindRunawayPolicy {
        if (maxCpuNanosPerCallback <= 0L || maxInstructionsPerCallback <= 0L) {
            throw new IllegalArgumentException("Mind runaway limits must be positive");
        }
    }
}
