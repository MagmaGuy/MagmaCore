package com.magmaguy.magmacore.match;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegistryTest extends MatchTestSupport {
    @Test
    void openRegistersTheMatchAndDestroyUnregistersIt() {
        TestMatch match = match(settings -> { });
        assertFalse(MatchCore.matches().contains(match));

        assertTrue(match.open());
        assertTrue(MatchCore.matches().contains(match));
        assertEquals(MatchPhase.WAITING, match.getPhase());

        match.destroy();
        assertFalse(MatchCore.matches().contains(match));
        assertEquals(MatchPhase.DESTROYED, match.getPhase());
        assertEquals(List.of("instantiateAttempt", "destroyed"), api.events);
        assertEquals(List.of("onDestroy"), match.hooks);
    }

    @Test
    void vetoedInstantiationNeverRegisters() {
        api.vetoInstantiate = true;
        TestMatch match = match(settings -> { });
        assertFalse(match.open());
        assertFalse(MatchCore.matches().contains(match));
        assertEquals(MatchPhase.DESTROYED, match.getPhase());
    }

    @Test
    void destroyRunsOnce() {
        TestMatch match = openMatch(settings -> { });
        match.destroy();
        match.destroy();
        assertEquals(1, api.events.stream().filter("destroyed"::equals).count());
    }

    @Test
    void shutdownDestroysOpenMatches() {
        TestMatch match = openMatch(settings -> { });
        MatchCore.shutdown();
        assertEquals(MatchPhase.DESTROYED, match.getPhase());
        MatchCore.enable(plugin);
    }
}
