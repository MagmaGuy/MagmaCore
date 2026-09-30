package com.magmaguy.magmacore.match;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Automatic endings go through requestEnd, and reusable region matches reset between runs. */
class ReuseTest extends MatchTestSupport {
    @Test
    void theLastPlayerLeavingRequestsADefeat() {
        TestMatch match = openMatch(settings -> { });
        var alex = player("Alex");
        match.admit(List.of(alex));
        match.leave(alex, LeaveReason.QUIT);
        assertTrue(match.hooks.contains("requestEnd:DEFEAT"));
        assertEquals(MatchOutcome.DEFEAT, match.getOutcome());
    }

    @Test
    void aSubclassCanDeclineAnAutomaticEnding() {
        TestMatch match = openMatch(settings -> settings.countdownSeconds(0));
        match.declineAutomaticEnds = true;
        var alex = player("Alex");
        match.admit(List.of(alex));
        match.leave(alex, LeaveReason.QUIT);
        assertEquals(MatchPhase.ONGOING, match.getPhase());
        assertNull(match.getOutcome());
    }

    @Test
    void aCountdownThatLosesItsMinimumRequestsANeutralEnd() {
        TestMatch match = openMatch(settings -> settings.players(2, 4));
        var alex = player("Alex");
        var bea = player("Bea");
        match.admit(List.of(alex, bea));
        match.start();
        match.leave(bea, LeaveReason.QUIT);
        server.getScheduler().performTicks(21);
        assertTrue(match.hooks.contains("requestEnd:NEUTRAL"));
    }

    @Test
    void aReusableMatchResetsToWaitingInsteadOfBeingDestroyed() {
        TestMatch match = openMatch(settings -> settings.reusable(true).countdownSeconds(0));
        var alex = player("Alex");
        match.admit(List.of(alex));
        match.end(MatchOutcome.VICTORY);

        assertEquals(MatchPhase.WAITING, match.getPhase());
        assertNull(match.getOutcome());
        assertTrue(match.isOpen());
        assertTrue(MatchCore.matches().contains(match));
        assertTrue(match.hooks.contains("onLeave:Alex:MATCH_ENDED"));
        assertTrue(match.hooks.contains("onReset"));
        assertFalse(match.hooks.contains("onDestroy"));
        assertTrue(api.events.contains("destroyed"), "each finished run is announced");
        assertTrue(match.getStartingRoster().isEmpty());
    }

    @Test
    void aResetMatchRunsAgain() {
        TestMatch match = openMatch(settings -> settings.reusable(true).countdownSeconds(0));
        match.admit(List.of(player("Alex")));
        match.end(MatchOutcome.VICTORY);

        var bea = player("Bea");
        assertEquals(AdmissionResult.ADMITTED, match.admit(List.of(bea)));
        assertEquals(MatchPhase.ONGOING, match.getPhase());
        assertEquals(2, match.hooks.stream().filter("onStart"::equals).count());
    }

    @Test
    void aStaleLingerFromThePreviousRunCannotResetTheNextOne() {
        TestMatch match = openMatch(settings -> settings.reusable(true).countdownSeconds(0).lingerAfterEndTicks(100));
        match.admit(List.of(player("Alex")));
        match.end(MatchOutcome.VICTORY);
        match.destroy();
        var bea = player("Bea");
        match.admit(List.of(bea));
        server.getScheduler().performTicks(150);
        assertSame(match, MatchCore.matchOf(bea));
        assertEquals(MatchPhase.ONGOING, match.getPhase());
    }

    @Test
    void retireDestroysAReusableMatchForGood() {
        TestMatch match = openMatch(settings -> settings.reusable(true));
        match.admit(List.of(player("Alex")));
        match.retire();
        assertEquals(MatchPhase.DESTROYED, match.getPhase());
        assertFalse(MatchCore.matches().contains(match));
        assertTrue(match.hooks.contains("onDestroy"));
    }

    @Test
    void shutdownRetiresReusableMatches() {
        TestMatch match = openMatch(settings -> settings.reusable(true));
        MatchCore.shutdown();
        assertEquals(MatchPhase.DESTROYED, match.getPhase());
        MatchCore.enable(plugin);
    }

    @Test
    void temporaryWorldsCannotBeReused() {
        assertThrows(IllegalArgumentException.class, () -> MatchSettings.builder()
                .space(MatchSpace.temporaryWorlds(arena))
                .reusable(true)
                .build());
    }
}
