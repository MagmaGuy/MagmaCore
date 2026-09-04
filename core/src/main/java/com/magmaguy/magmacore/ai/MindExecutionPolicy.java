package com.magmaguy.magmacore.ai;

/**
 * Soft per-tick scheduling budgets used to defer later Mind work under load.
 *
 * <p>A callback that returns has completed successfully even when it crosses one of these time
 * budgets. Hard script interruption belongs to {@link MindRunawayPolicy}.</p>
 */
public record MindExecutionPolicy(
        long maxCallbackNanos,
        long maxEntityNanosPerTick,
        long maxServerNanosPerTick,
        int maxCallbacksPerEntityTick,
        int maxActionRequestsPerEntityTick,
        MindRunawayPolicy runawayPolicy) {

    /** Absolute native safety ceiling. Lua programs may choose a lower cap. */
    public static final int ACTION_REQUESTS_PER_ENTITY_TICK_HARD_LIMIT = 64;

    public static final MindExecutionPolicy DEFAULT = new MindExecutionPolicy(
            2_000_000L,
            4_000_000L,
            20_000_000L,
            128,
            8,
            MindRunawayPolicy.DEFAULT);

    /** Source-compatible constructor for the original combined scheduling/Lua policy. */
    public MindExecutionPolicy(
            long maxCallbackNanos,
            long maxEntityNanosPerTick,
            long maxServerNanosPerTick,
            int maxCallbacksPerEntityTick,
            long maxLuaInstructionsPerCallback,
            int maxActionRequestsPerEntityTick) {
        this(
                maxCallbackNanos,
                maxEntityNanosPerTick,
                maxServerNanosPerTick,
                maxCallbacksPerEntityTick,
                maxActionRequestsPerEntityTick,
                new MindRunawayPolicy(
                        MindRunawayPolicy.DEFAULT.maxCpuNanosPerCallback(),
                        maxLuaInstructionsPerCallback));
    }

    /** Source-compatible constructor using the default semantic action-request cap. */
    public MindExecutionPolicy(
            long maxCallbackNanos,
            long maxEntityNanosPerTick,
            long maxServerNanosPerTick,
            int maxCallbacksPerEntityTick,
            long maxLuaInstructionsPerCallback) {
        this(
                maxCallbackNanos,
                maxEntityNanosPerTick,
                maxServerNanosPerTick,
                maxCallbacksPerEntityTick,
                DEFAULT.maxActionRequestsPerEntityTick,
                new MindRunawayPolicy(
                        MindRunawayPolicy.DEFAULT.maxCpuNanosPerCallback(),
                        maxLuaInstructionsPerCallback));
    }

    public MindExecutionPolicy {
        if (maxCallbackNanos <= 0
                || maxEntityNanosPerTick <= 0
                || maxServerNanosPerTick <= 0
                || maxCallbacksPerEntityTick <= 0
                || maxActionRequestsPerEntityTick <= 0) {
            throw new IllegalArgumentException("All mind scheduling limits must be positive");
        }
        if (runawayPolicy == null) throw new NullPointerException("runawayPolicy");
        if (maxCallbackNanos > maxEntityNanosPerTick) {
            throw new IllegalArgumentException("Callback budget cannot exceed entity budget");
        }
        if (maxEntityNanosPerTick > maxServerNanosPerTick) {
            throw new IllegalArgumentException("Entity budget cannot exceed server budget");
        }
        if (maxActionRequestsPerEntityTick > ACTION_REQUESTS_PER_ENTITY_TICK_HARD_LIMIT) {
            throw new IllegalArgumentException(
                    "Mind action-request cap cannot exceed the native hard limit of "
                            + ACTION_REQUESTS_PER_ENTITY_TICK_HARD_LIMIT);
        }
    }

    /** Legacy accessor retained for source compatibility. */
    public long maxLuaInstructionsPerCallback() {
        return runawayPolicy.maxInstructionsPerCallback();
    }
}
