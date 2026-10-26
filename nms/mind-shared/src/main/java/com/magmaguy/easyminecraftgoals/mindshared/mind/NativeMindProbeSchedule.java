package com.magmaguy.easyminecraftgoals.mindshared.mind;

import java.util.UUID;

/**
 * When a stopped behavior's {@code canStart} runs, every {@link com.magmaguy.magmacore.ai.MindBehavior#probeInterval()}
 * ticks, or a sensor senses, every {@link com.magmaguy.magmacore.ai.MindSensor#intervalTicks()} ticks. Each schedule
 * runs on its own grid: the ticks whose offset from a phase, taken from the entity's logical owner and the behavior's
 * or sensor's identifier, is a whole number of intervals. Entities created on the same tick, and the callbacks of one
 * entity, therefore fall on different ticks, and they stay apart: a run deferred to the next tick, or a behavior that
 * many entities stop on the same tick, goes back onto its own grid afterwards. A schedule made to run at once runs on
 * the first tick it sees, as a sensor's setup on spawn needs, and keeps to its grid from then on. An interval of 1
 * runs every tick.
 */
final class NativeMindProbeSchedule {
    private final int interval;
    private final int phase;
    private final boolean firstAtOnce;
    private boolean scheduled;
    private long nextTick;

    /** {@code interval} is in ticks; anything below 1 means 1. The first run waits for the grid. */
    NativeMindProbeSchedule(int interval, UUID owner, String identifier) {
        this(interval, owner, identifier, false);
    }

    /** As above; with {@code firstAtOnce}, the first run is on the first tick this schedule sees. */
    NativeMindProbeSchedule(int interval, UUID owner, String identifier, boolean firstAtOnce) {
        this.interval = Math.max(1, interval);
        this.phase = phase(owner, identifier, this.interval);
        this.firstAtOnce = firstAtOnce;
    }

    /**
     * The grid's offset, 0 to interval - 1. A mix of the owner's bits and the identifier, so it spreads evenly over a
     * crowd and stays the same across reloads.
     */
    static int phase(UUID owner, String identifier, int interval) {
        if (interval <= 1) return 0;
        long seed = owner == null ? 0L : owner.getMostSignificantBits() ^ owner.getLeastSignificantBits();
        seed = seed * 0x9E3779B97F4A7C15L + (identifier == null ? 0 : identifier.hashCode());
        seed = (seed ^ (seed >>> 30)) * 0xBF58476D1CE4E5B9L;
        seed = (seed ^ (seed >>> 27)) * 0x94D049BB133111EBL;
        seed ^= seed >>> 31;
        return (int) Math.floorMod(seed, (long) interval);
    }

    /** Whether the callback runs on this tick. */
    boolean due(long tick) {
        if (interval == 1) return true;
        if (!scheduled) {
            nextTick = firstAtOnce ? tick : gridAtOrAfter(tick);
            scheduled = true;
        } else if (nextTick - tick > interval) {
            // A body moved to a world whose clock is behind would otherwise wait for that clock to catch up.
            nextTick = gridAtOrAfter(tick);
        }
        return tick >= nextTick;
    }

    /** The callback ran on this tick: the next run is on the first grid tick after it. */
    void ran(long tick) {
        if (interval == 1) return;
        nextTick = gridAfter(tick);
        scheduled = true;
    }

    /** The behavior stopped on this tick: it is next asked on the first grid tick after it. */
    void stopped(long tick) {
        ran(tick);
    }

    /** The scheduler deferred what the callback allowed: run again on the next tick, then back on the grid. */
    void retry(long tick) {
        if (interval == 1) return;
        nextTick = tick == Long.MAX_VALUE ? Long.MAX_VALUE : tick + 1;
        scheduled = true;
    }

    private long gridAtOrAfter(long tick) {
        long wait = Math.floorMod(phase - tick, (long) interval);
        return tick > Long.MAX_VALUE - wait ? Long.MAX_VALUE : tick + wait;
    }

    private long gridAfter(long tick) {
        long wait = interval - Math.floorMod(tick - phase, (long) interval);
        return tick > Long.MAX_VALUE - wait ? Long.MAX_VALUE : tick + wait;
    }

    int interval() {
        return interval;
    }

    int phase() {
        return phase;
    }
}
