package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.ai.MindBehavior;
import com.magmaguy.magmacore.ai.MindProgram;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaMindProbeIntervalTest {
    private static MindBehavior behavior(String probeInterval) {
        String key = probeInterval == null ? "" : "probe_interval = " + probeInterval + ", ";
        MindProgram program = LuaMindDefinition.validate("probe.lua", """
                return ai.program { id = "fixture:probe", revision = 1,
                  behaviors = { ai.behavior { id = "fixture:probe", %s
                    can_start = function(c) return true end, tick = function(c) end } } }
                """.formatted(key)).instantiate();
        return program.behaviors().get(0);
    }

    @Test
    void aBehaviorWithoutOneIsProbedEveryTick() {
        assertEquals(1, behavior(null).probeInterval());
    }

    @Test
    void aWholeNumberOfTicksIsKept() {
        assertEquals(1, behavior("1").probeInterval());
        assertEquals(10, behavior("10").probeInterval());
        assertEquals(10, behavior("10.0").probeInterval());
    }

    @Test
    void aLongerIntervalIsCappedLikeTheRunawayLimits() {
        assertEquals(MindBehavior.MAX_PROBE_INTERVAL, behavior("1e9").probeInterval());
        assertEquals(MindBehavior.MAX_PROBE_INTERVAL, behavior("math.huge").probeInterval());
    }

    @Test
    void anythingButAWholeNumberOfAtLeastOneTickIsRefused() {
        for (String refused : new String[]{"0", "-4", "2.5", "0/0", "-math.huge", "'5'", "true", "{}"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> behavior(refused),
                    refused);
            assertTrue(error.getMessage().contains("probe_interval"), refused);
        }
    }
}
