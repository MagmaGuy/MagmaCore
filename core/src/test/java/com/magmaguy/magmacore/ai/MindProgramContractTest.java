package com.magmaguy.magmacore.ai;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MindProgramContractTest {
    @Test
    void immutableControlSetDoesNotRequireNullLookupSupport() {
        MindBehavior behavior = new MindBehavior() {
            @Override
            public String identifier() {
                return "test:immutable_controls";
            }

            @Override
            public Set<MindControl> controls() {
                return Set.of(MindControl.MOVE, MindControl.LOOK);
            }

            @Override
            public boolean canStart(MindContext context) {
                return true;
            }

            @Override
            public void tick(MindContext context) {
            }
        };

        assertDoesNotThrow(() -> MindProgram.builder("test:program", 1L)
                .behavior(behavior)
                .build());
    }

    @Test
    void aBehaviorIsProbedEveryTickByDefault() {
        assertEquals(1, probing(null).probeInterval());
    }

    @Test
    void probeIntervalsFromOneToTheCapBuild() {
        for (int interval : new int[]{1, 5, MindBehavior.MAX_PROBE_INTERVAL}) {
            assertDoesNotThrow(() -> MindProgram.builder("test:program", 1L).behavior(probing(interval)).build());
        }
    }

    @Test
    void probeIntervalsOutsideOneToTheCapAreRefused() {
        for (int interval : new int[]{0, -3, MindBehavior.MAX_PROBE_INTERVAL + 1}) {
            assertThrows(IllegalStateException.class,
                    () -> MindProgram.builder("test:program", 1L).behavior(probing(interval)).build());
        }
    }

    /** A behavior with this probe interval, or the default one when it is null. */
    private static MindBehavior probing(Integer interval) {
        return new MindBehavior() {
            @Override
            public String identifier() {
                return "test:probing";
            }

            @Override
            public int probeInterval() {
                return interval == null ? MindBehavior.super.probeInterval() : interval;
            }

            @Override
            public boolean canStart(MindContext context) {
                return true;
            }

            @Override
            public void tick(MindContext context) {
            }
        };
    }
}
