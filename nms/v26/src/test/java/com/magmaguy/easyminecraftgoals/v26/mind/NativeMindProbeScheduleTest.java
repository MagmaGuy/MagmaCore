package com.magmaguy.easyminecraftgoals.v26.mind;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The schedule lives in nms/mind-shared; this adapter compiles that source, so its tests cover it. */
class NativeMindProbeScheduleTest {
    private static List<Long> ranTicks(NativeMindProbeSchedule schedule, long from, long to) {
        List<Long> ran = new ArrayList<>();
        for (long tick = from; tick < to; tick++) {
            if (!schedule.due(tick)) continue;
            ran.add(tick);
            schedule.ran(tick);
        }
        return ran;
    }

    /** The first tick at or after {@code tick} on the schedule's grid. */
    private static long grid(NativeMindProbeSchedule schedule, long tick) {
        return tick + Math.floorMod(schedule.phase() - tick, (long) schedule.interval());
    }

    @Test
    void anIntervalOfOneRunsEveryTick() {
        NativeMindProbeSchedule schedule = new NativeMindProbeSchedule(1, UUID.randomUUID(), "fixture:every");
        assertEquals(List.of(10L, 11L, 12L, 13L, 14L), ranTicks(schedule, 10L, 15L));
    }

    @Test
    void anIntervalOfFiveRunsEveryFifthTickOnItsGrid() {
        UUID owner = new UUID(12L, 34L);
        NativeMindProbeSchedule schedule = new NativeMindProbeSchedule(5, owner, "fixture:drift");
        long first = grid(schedule, 100L);
        assertEquals(0L, Math.floorMod(first - NativeMindProbeSchedule.phase(owner, "fixture:drift", 5), 5L));
        assertEquals(List.of(first, first + 5, first + 10, first + 15), ranTicks(schedule, 100L, first + 16));
    }

    @Test
    void phasesSpreadOverACrowdAndStayTheSameForAnOwner() {
        Random random = new Random(7L);
        Set<Integer> phases = new HashSet<>();
        for (int index = 0; index < 100; index++) {
            UUID owner = new UUID(random.nextLong(), random.nextLong());
            int phase = NativeMindProbeSchedule.phase(owner, "fixture:drift", 10);
            assertTrue(phase >= 0 && phase < 10);
            assertEquals(phase, NativeMindProbeSchedule.phase(owner, "fixture:drift", 10));
            phases.add(phase);
        }
        assertEquals(10, phases.size(), "100 owners cover every phase of a 10-tick interval");
    }

    @Test
    void anIntervalBelowOneIsOneAndALongSensorIntervalIsKept() {
        assertEquals(1, new NativeMindProbeSchedule(0, null, "fixture:x").interval());
        assertEquals(1_200, new NativeMindProbeSchedule(1_200, null, "fixture:x").interval());
    }

    @Test
    void aSkippedRunIsTriedOnTheNextTickThenGoesBackOnTheGrid() {
        NativeMindProbeSchedule schedule = new NativeMindProbeSchedule(10, null, "fixture:x");
        long first = grid(schedule, 0L);
        assertTrue(schedule.due(first));
        // Skipped for budget: nothing ran.
        assertTrue(schedule.due(first + 1));
        schedule.ran(first + 1);
        assertFalse(schedule.due(first + 2));
        assertFalse(schedule.due(first + 9));
        assertTrue(schedule.due(first + 10), "back on its grid, not 10 ticks after the late run");
    }

    @Test
    void aDeferredStartRetriesOnTheNextTickThenGoesBackOnTheGrid() {
        NativeMindProbeSchedule schedule = new NativeMindProbeSchedule(10, null, "fixture:x");
        long first = grid(schedule, 0L);
        assertTrue(schedule.due(first));
        schedule.ran(first);
        schedule.retry(first);
        assertTrue(schedule.due(first + 1));
        schedule.ran(first + 1);
        assertFalse(schedule.due(first + 2));
        assertTrue(schedule.due(first + 10));
    }

    @Test
    void aBehaviorThatStopsIsAskedAgainOnItsGrid() {
        NativeMindProbeSchedule schedule = new NativeMindProbeSchedule(10, null, "fixture:x");
        long first = grid(schedule, 0L);
        assertTrue(schedule.due(first));
        schedule.ran(first);
        // It started, ran, and stopped off its grid.
        schedule.stopped(first + 13);
        assertFalse(schedule.due(first + 14));
        assertFalse(schedule.due(first + 19));
        assertTrue(schedule.due(first + 20));
    }

    @Test
    void schedulesThatStopOrAreSkippedTogetherStayApart() {
        NativeMindProbeSchedule one = new NativeMindProbeSchedule(10, new UUID(0L, 0L), "fixture:x");
        UUID otherOwner = new UUID(0L, 1L);
        while (NativeMindProbeSchedule.phase(otherOwner, "fixture:x", 10) == one.phase()) {
            otherOwner = new UUID(0L, otherOwner.getLeastSignificantBits() + 1L);
        }
        NativeMindProbeSchedule other = new NativeMindProbeSchedule(10, otherOwner, "fixture:x");
        // Both stop on tick 500, then both are skipped from 600 until 610 and run together on 610.
        one.stopped(500L);
        other.stopped(500L);
        assertNotEquals(ranTicks(one, 501L, 600L), ranTicks(other, 501L, 600L));
        for (long tick = 600L; tick < 610L; tick++) {
            one.due(tick);
            other.due(tick);
        }
        assertTrue(one.due(610L) && other.due(610L));
        one.ran(610L);
        other.ran(610L);
        List<Long> oneAfter = ranTicks(one, 611L, 640L), otherAfter = ranTicks(other, 611L, 640L);
        assertEquals(List.of(grid(one, 611L), grid(one, 611L) + 10), oneAfter.subList(0, 2));
        assertEquals(List.of(grid(other, 611L), grid(other, 611L) + 10), otherAfter.subList(0, 2));
        assertNotEquals(oneAfter, otherAfter);
    }

    @Test
    void aClockThatJumpsBackReschedulesInsteadOfWaitingForItToCatchUp() {
        NativeMindProbeSchedule schedule = new NativeMindProbeSchedule(10, null, "fixture:x");
        long first = grid(schedule, 1_000_000L);
        assertTrue(schedule.due(first));
        schedule.ran(first);
        // The body moved to a world whose clock is far behind.
        List<Long> ran = ranTicks(schedule, 50L, 80L);
        assertEquals(3, ran.size());
        assertEquals(grid(schedule, 50L), ran.get(0));
    }
}
