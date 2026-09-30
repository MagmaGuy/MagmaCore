package com.magmaguy.magmacore.match;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PendingMatchTest extends MatchTestSupport {
    @Test
    void requestMarksTheRosterAsPending() {
        var alex = player("Alex");
        PendingMatch pending = PendingMatch.request(List.of(alex), 200);
        assertNotNull(pending);
        assertEquals("MatchTest:pending", alex.getMetadata(MatchCore.MARKER_KEY).getFirst().asString());
        assertNull(PendingMatch.request(List.of(alex), 200));
        assertEquals(AdmissionResult.ALREADY_IN_MATCH, openMatch(settings -> { }).admit(List.of(alex)));
    }

    @Test
    void playersAlreadyInAMatchCannotRequest() {
        var alex = player("Alex");
        openMatch(settings -> { }).admit(List.of(alex));
        assertNull(PendingMatch.request(List.of(alex), 200));
    }

    @Test
    void completeOpensAndAdmitsTheRoster() {
        var alex = player("Alex");
        PendingMatch pending = PendingMatch.request(List.of(alex), 200);
        List<PendingMatch.CancelReason> reasons = new ArrayList<>();
        pending.onCancel(reasons::add);
        TestMatch match = match(settings -> { });

        assertEquals(AdmissionResult.ADMITTED, pending.complete(match));

        assertTrue(match.isOpen());
        assertSame(match, MatchCore.matchOf(alex));
        assertFalse(pending.isActive());
        assertTrue(reasons.isEmpty(), "completing is not cancelling");
        server.getScheduler().performTicks(300);
        assertTrue(reasons.isEmpty(), "the timeout is cancelled by completion");
    }

    @Test
    void quittingCancelsAndRunsCleanup() {
        var alex = player("Alex");
        PendingMatch pending = PendingMatch.request(List.of(alex), 200);
        List<PendingMatch.CancelReason> reasons = new ArrayList<>();
        pending.onCancel(reasons::add);
        alex.disconnect();
        assertEquals(List.of(PendingMatch.CancelReason.QUIT), reasons);
        assertFalse(pending.isActive());
    }

    @Test
    void timeoutCancels() {
        var alex = player("Alex");
        PendingMatch pending = PendingMatch.request(List.of(alex), 200);
        List<PendingMatch.CancelReason> reasons = new ArrayList<>();
        pending.onCancel(reasons::add);
        server.getScheduler().performTicks(199);
        assertTrue(pending.isActive());
        server.getScheduler().performTicks(1);
        assertEquals(List.of(PendingMatch.CancelReason.TIMEOUT), reasons);
        assertTrue(alex.getMetadata(MatchCore.MARKER_KEY).isEmpty());
    }

    @Test
    void cleanupRegisteredAfterCancellationRunsAtOnce() {
        var alex = player("Alex");
        PendingMatch pending = PendingMatch.request(List.of(alex), 200);
        pending.cancel();
        List<PendingMatch.CancelReason> reasons = new ArrayList<>();
        pending.onCancel(reasons::add);
        assertEquals(List.of(PendingMatch.CancelReason.CANCELLED), reasons);
    }

    @Test
    void completingACancelledRequestDestroysTheMatchWithoutAnnouncingIt() {
        var alex = player("Alex");
        PendingMatch pending = PendingMatch.request(List.of(alex), 200);
        pending.cancel();
        TestMatch match = match(settings -> { });
        assertEquals(AdmissionResult.NOT_ACCEPTING, pending.complete(match));
        assertEquals(MatchPhase.DESTROYED, match.getPhase());
        assertTrue(match.hooks.contains("onDestroy"), "the plugin still cleans up what it built");
        assertFalse(api.events.contains("destroyed"), "the API never saw this match open");
    }

    @Test
    void aFailedAdmissionOnCompleteDestroysTheMatch() {
        var alex = player("Alex");
        PendingMatch pending = PendingMatch.request(List.of(alex), 200);
        api.vetoJoin.add("Alex");
        TestMatch match = match(settings -> { });
        assertEquals(AdmissionResult.VETOED, pending.complete(match));
        assertEquals(MatchPhase.DESTROYED, match.getPhase());
        assertTrue(alex.getMetadata(MatchCore.MARKER_KEY).isEmpty());
    }

    @Test
    void shutdownCancelsPendingRequests() {
        var alex = player("Alex");
        PendingMatch pending = PendingMatch.request(List.of(alex), 200);
        List<PendingMatch.CancelReason> reasons = new ArrayList<>();
        pending.onCancel(reasons::add);
        MatchCore.shutdown();
        assertEquals(List.of(PendingMatch.CancelReason.CANCELLED), reasons);
        MatchCore.enable(plugin);
    }
}
