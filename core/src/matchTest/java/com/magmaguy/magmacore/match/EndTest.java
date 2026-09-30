package com.magmaguy.magmacore.match;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EndTest extends MatchTestSupport {
    @Test
    void endRunsOnceAndHealsEveryone() {
        TestMatch match = openMatch(settings -> settings.lingerAfterEndTicks(100));
        var alex = player("Alex");
        match.admit(List.of(alex));
        alex.setHealth(5);
        match.end(MatchOutcome.VICTORY);
        match.end(MatchOutcome.DEFEAT);
        assertEquals(List.of("onEnd:VICTORY"),
                match.hooks.stream().filter(hook -> hook.startsWith("onEnd")).toList());
        assertEquals(List.of("ended:VICTORY"),
                api.events.stream().filter(event -> event.startsWith("ended")).toList());
        assertEquals(MatchOutcome.VICTORY, match.getOutcome());
        assertEquals(20.0, alex.getHealth());
    }

    @Test
    void participantsStayUntilTheLingerRunsOut() {
        TestMatch match = openMatch(settings -> settings.lingerAfterEndTicks(100));
        var alex = player("Alex");
        match.admit(List.of(alex));
        server.getScheduler().performTicks(1);
        match.end(MatchOutcome.VICTORY);
        server.getScheduler().performTicks(99);
        assertSame(match, MatchCore.matchOf(alex));
        server.getScheduler().performTicks(1);
        assertNull(MatchCore.matchOf(alex));
        assertTrue(match.hooks.contains("onLeave:Alex:MATCH_ENDED"));
        assertEquals(MatchPhase.DESTROYED, match.getPhase());
    }

    @Test
    void anEmptyMatchSkipsTheLinger() {
        TestMatch match = openMatch(settings -> settings.lingerAfterEndTicks(100));
        var alex = player("Alex");
        match.admit(List.of(alex));
        match.leave(alex, LeaveReason.QUIT);
        assertEquals(MatchPhase.DESTROYED, match.getPhase());
    }

    @Test
    void subclassesCanScheduleTheirOwnDestroy() {
        TestMatch match = openMatch(settings -> settings.lingerAfterEndTicks(1000));
        match.admit(List.of(player("Alex")));
        match.end(MatchOutcome.VICTORY);
        match.scheduleDestroy(10);
        server.getScheduler().performTicks(10);
        assertEquals(MatchPhase.DESTROYED, match.getPhase());
    }

    @Test
    void destroyWithoutEndIsNeutralAndDoesNotHeal() {
        TestMatch match = openMatch(settings -> { });
        var alex = player("Alex");
        match.admit(List.of(alex));
        alex.setHealth(5);
        match.destroy();
        assertEquals(MatchOutcome.NEUTRAL, match.getOutcome());
        assertEquals(5.0, alex.getHealth());
        assertTrue(match.hooks.contains("onLeave:Alex:MATCH_ENDED"));
    }

    @Test
    void shutdownRemovesParticipantsWithShutdown() {
        TestMatch match = openMatch(settings -> { });
        match.admit(List.of(player("Alex")));
        MatchCore.shutdown();
        assertTrue(match.hooks.contains("onLeave:Alex:SHUTDOWN"));
        MatchCore.enable(plugin);
    }

    @Test
    void aThrowingHookCannotStopTeardown() {
        TestMatch match = openMatch(settings -> { });
        var alex = player("Alex");
        match.admit(List.of(alex));
        server.getScheduler().performTicks(1);
        match.throwOnLeave = new IllegalStateException("boom");
        match.destroy();
        assertNull(MatchCore.matchOf(alex));
        assertEquals(overworld, alex.getWorld());
        assertTrue(api.events.contains("destroyed"));
    }
}
