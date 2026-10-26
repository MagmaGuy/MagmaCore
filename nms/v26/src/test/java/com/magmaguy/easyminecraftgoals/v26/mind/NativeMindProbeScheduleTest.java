package com.magmaguy.easyminecraftgoals.v26.mind;

import com.magmaguy.magmacore.ai.MindBehavior;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The probe schedule lives in nms/mind-shared; this adapter compiles that source, so its tests cover it. */
class NativeMindProbeScheduleTest {
    private static List<Long> askedTicks(NativeMindProbeSchedule schedule, long from, long to) {
        List<Long> asked = new ArrayList<>();
        for (long tick = from; tick < to; tick++) {
            if (!schedule.due(tick)) continue;
            asked.add(tick);
            schedule.asked(tick);
        }
        return asked;
    }

    @Test
    void anIntervalOfOneAsksEveryTick() {
        NativeMindProbeSchedule schedule = new NativeMindProbeSchedule(1, UUID.randomUUID(), "fixture:every");
        assertEquals(List.of(10L, 11L, 12L, 13L, 14L), askedTicks(schedule, 10L, 15L));
    }

    @Test
    void anIntervalOfFiveAsksEveryFifthTickFromItsPhase() {
        UUID owner = new UUID(12L, 34L);
        NativeMindProbeSchedule schedule = new NativeMindProbeSchedule(5, owner, "fixture:drift");
        long first = 100L + NativeMindProbeSchedule.phase(owner, "fixture:drift", 5);
        assertEquals(List.of(first, first + 5, first + 10, first + 15), askedTicks(schedule, 100L, first + 16));
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
    void anOutOfRangeIntervalIsHeldInsideOneToTheCap() {
        assertEquals(1, new NativeMindProbeSchedule(0, null, "fixture:x").interval());
        assertEquals(MindBehavior.MAX_PROBE_INTERVAL,
                new NativeMindProbeSchedule(1_000, null, "fixture:x").interval());
    }

    @Test
    void aDeferredStartAsksAgainOnTheNextTick() {
        NativeMindProbeSchedule schedule = new NativeMindProbeSchedule(10, null, "fixture:x");
        // The first tick it sees schedules its first ask a phase later.
        assertEquals(schedule.phase() == 0, schedule.due(0L));
        long first = schedule.phase();
        assertTrue(schedule.due(first));
        schedule.asked(first);
        schedule.retry(first);
        assertTrue(schedule.due(first + 1));
        schedule.asked(first + 1);
        assertFalse(schedule.due(first + 2));
        assertTrue(schedule.due(first + 11));
    }

    @Test
    void aClockThatJumpsBackReschedulesInsteadOfWaitingForItToCatchUp() {
        NativeMindProbeSchedule schedule = new NativeMindProbeSchedule(10, null, "fixture:x");
        assertEquals(schedule.phase() == 0, schedule.due(1_000_000L));
        long first = 1_000_000L + schedule.phase();
        assertTrue(schedule.due(first));
        schedule.asked(first);
        // The body moved to a world whose clock is far behind.
        List<Long> asked = askedTicks(schedule, 50L, 80L);
        assertEquals(3, asked.size());
        assertEquals(50L + schedule.phase(), asked.get(0));
    }
}
