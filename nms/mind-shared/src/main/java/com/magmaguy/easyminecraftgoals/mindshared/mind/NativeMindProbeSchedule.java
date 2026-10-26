package com.magmaguy.easyminecraftgoals.mindshared.mind;

import com.magmaguy.magmacore.ai.MindBehavior;

import java.util.UUID;

/**
 * When a stopped behavior's {@code canStart} runs: every {@link MindBehavior#probeInterval()} ticks. The first ask
 * waits a phase taken from the entity's logical owner and the behavior's identifier, so entities created on the same
 * tick, and the behaviors of one entity, ask on different ticks. An interval of 1 asks every tick.
 */
final class NativeMindProbeSchedule {
    private final int interval;
    private final int phase;
    private boolean scheduled;
    private long nextTick;

    NativeMindProbeSchedule(int interval, UUID owner, String behavior) {
        this.interval = Math.max(1, Math.min(MindBehavior.MAX_PROBE_INTERVAL, interval));
        this.phase = phase(owner, behavior, this.interval);
    }

    /**
     * The tick offset, 0 to interval - 1, at which this owner's behavior first asks. A mix of the owner's bits and the
     * behavior's identifier, so it spreads evenly over a crowd and stays the same across reloads.
     */
    static int phase(UUID owner, String behavior, int interval) {
        if (interval <= 1) return 0;
        long seed = owner == null ? 0L : owner.getMostSignificantBits() ^ owner.getLeastSignificantBits();
        seed = seed * 0x9E3779B97F4A7C15L + (behavior == null ? 0 : behavior.hashCode());
        seed = (seed ^ (seed >>> 30)) * 0xBF58476D1CE4E5B9L;
        seed = (seed ^ (seed >>> 27)) * 0x94D049BB133111EBL;
        seed ^= seed >>> 31;
        return (int) Math.floorMod(seed, (long) interval);
    }

    /** Whether {@code canStart} runs on this tick. */
    boolean due(long tick) {
        if (interval == 1) return true;
        // A body moved to a world whose clock is behind would otherwise wait for that clock to catch up.
        if (!scheduled || nextTick - tick > interval) {
            nextTick = tick + phase;
            scheduled = true;
        }
        return tick >= nextTick;
    }

    /** {@code canStart} ran on this tick: the next ask is a whole interval later. */
    void asked(long tick) {
        if (interval == 1) return;
        nextTick = tick > Long.MAX_VALUE - interval ? Long.MAX_VALUE : tick + interval;
        scheduled = true;
    }

    /** {@code canStart} allowed a start the scheduler then deferred: ask again on the next tick. */
    void retry(long tick) {
        if (interval == 1) return;
        nextTick = tick == Long.MAX_VALUE ? Long.MAX_VALUE : tick + 1;
        scheduled = true;
    }

    int interval() {
        return interval;
    }

    int phase() {
        return phase;
    }
}
