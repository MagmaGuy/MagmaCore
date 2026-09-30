package com.magmaguy.magmacore.match;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class GroupAdmissionTest extends MatchTestSupport {
    @Test
    void groupLargerThanTheRemainingRoomIsRejectedWhole() {
        TestMatch match = openMatch(settings -> settings.players(1, 2));
        var group = List.of(player("Alex"), player("Bea"), player("Cam"));
        assertEquals(AdmissionResult.FULL, match.admit(group));
        group.forEach(member -> {
            assertNull(MatchCore.matchOf(member));
            assertTrue(member.getMetadata(MatchCore.MARKER_KEY).isEmpty());
        });
    }

    @Test
    void oneVetoRejectsTheWholeGroup() {
        TestMatch match = openMatch(settings -> { });
        api.vetoJoin.add("Bea");
        var group = List.of(player("Alex"), player("Bea"));
        assertEquals(AdmissionResult.VETOED, match.admit(group));
        assertTrue(match.getMatchPlayers().isEmpty());
        assertFalse(api.events.stream().anyMatch(event -> event.startsWith("joined")));
    }

    @Test
    void authorizationWithdrawnDuringACallbackRejectsTheGroup() {
        TestMatch match = openMatch(settings -> { });
        AtomicBoolean authorized = new AtomicBoolean(true);
        api.duringJoinAttempt = () -> authorized.set(false);
        assertEquals(AdmissionResult.NOT_AUTHORIZED,
                match.admit(List.of(player("Alex"), player("Bea")), authorized::get));
        assertTrue(match.getMatchPlayers().isEmpty());
    }

    @Test
    void aCallbackThatFillsTheMatchRejectsTheGroupOnTheRecheck() {
        TestMatch match = openMatch(settings -> settings.players(1, 2));
        var latecomer = player("Latecomer");
        api.duringJoinAttempt = () -> {
            api.duringJoinAttempt = null;
            match.admit(List.of(latecomer));
        };
        assertEquals(AdmissionResult.FULL, match.admit(List.of(player("Alex"), player("Bea"))));
        assertEquals(1, match.getMatchPlayers().size());
    }

    @Test
    void refusedReservationRejects() {
        TestMatch match = openMatch(settings -> { });
        match.refuseReservation = true;
        assertEquals(AdmissionResult.NOT_ACCEPTING, match.admit(List.of(player("Alex"))));
        assertTrue(match.getMatchPlayers().isEmpty());
    }

    @Test
    void duplicatePlayersInTheRequestAreAdmittedOnce() {
        TestMatch match = openMatch(settings -> { });
        var alex = player("Alex");
        assertEquals(AdmissionResult.ADMITTED, match.admit(List.of(alex, alex)));
        assertEquals(1, match.getMatchPlayers().size());
    }

    @Test
    void wholeGroupEntersTogether() {
        TestMatch match = openMatch(settings -> { });
        var group = List.of(player("Alex"), player("Bea"));
        assertEquals(AdmissionResult.ADMITTED, match.admit(group));
        server.getScheduler().performTicks(1);
        group.forEach(member -> assertEquals(arena, member.getWorld()));
    }
}
