package com.magmaguy.magmacore.match;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StartTest extends MatchTestSupport {
    @Test
    void startNeedsTheMinimumPlayers() {
        TestMatch match = openMatch(settings -> settings.players(2, 4));
        var alex = player("Alex");
        match.admit(List.of(alex));
        drainMessages(alex);
        assertEquals(StartResult.NOT_ENOUGH_PLAYERS, match.start());
        assertEquals("§cThis match needs 2 players to start.", alex.nextMessage());
        assertEquals(MatchPhase.WAITING, match.getPhase());
    }

    @Test
    void countdownRunsThreeSecondsThenStarts() {
        TestMatch match = openMatch(settings -> settings.players(2, 4));
        var group = List.of(player("Alex"), player("Bea"));
        match.admit(group);
        server.getScheduler().performTicks(1);

        assertEquals(StartResult.STARTED, match.start());
        assertEquals(MatchPhase.STARTING, match.getPhase());
        server.getScheduler().performTicks(39);
        assertEquals(MatchPhase.STARTING, match.getPhase());
        server.getScheduler().performTicks(2);
        assertEquals(MatchPhase.ONGOING, match.getPhase());
        assertEquals(1, match.hooks.stream().filter("onStart"::equals).count());
        assertTrue(api.events.containsAll(List.of("startAttempt", "started")));
        assertEquals(Set.of(group.get(0).getUniqueId(), group.get(1).getUniqueId()), match.getStartingRoster());
    }

    @Test
    void startTeleportsEnteredPlayersToTheStart() {
        TestMatch match = openMatch(settings -> settings.lobby(at(arena, 20.5, 64, 20.5)));
        var alex = player("Alex");
        match.admit(List.of(alex));
        server.getScheduler().performTicks(1);
        assertEquals(at(arena, 20.5, 64, 20.5), alex.getLocation());
        match.start();
        server.getScheduler().performTicks(41);
        assertEquals(at(arena, 0.5, 64, 0.5), alex.getLocation());
    }

    @Test
    void startingIsOnlyPossibleWhileWaiting() {
        TestMatch match = openMatch(settings -> { });
        match.admit(List.of(player("Alex")));
        match.start();
        assertEquals(StartResult.NOT_WAITING, match.start());
    }

    @Test
    void vetoedStartKeepsWaiting() {
        TestMatch match = openMatch(settings -> { });
        match.admit(List.of(player("Alex")));
        api.vetoStart = true;
        assertEquals(StartResult.VETOED, match.start());
        assertEquals(MatchPhase.WAITING, match.getPhase());
    }

    @Test
    void droppingBelowTheMinimumDuringTheCountdownEndsTheMatch() {
        TestMatch match = openMatch(settings -> settings.players(2, 4));
        var alex = player("Alex");
        var bea = player("Bea");
        match.admit(List.of(alex, bea));
        match.start();
        match.leave(bea, LeaveReason.QUIT);
        server.getScheduler().performTicks(21);
        assertEquals(MatchOutcome.NEUTRAL, match.getOutcome());
        assertEquals(MatchPhase.DESTROYED, match.getPhase());
    }

    @Test
    void noCountdownStartsOnAdmission() {
        TestMatch match = openMatch(settings -> settings.countdownSeconds(0));
        var alex = player("Alex");
        match.admit(List.of(alex));
        assertEquals(MatchPhase.ONGOING, match.getPhase());
        server.getScheduler().performTicks(1);
        assertEquals(at(arena, 0.5, 64, 0.5), alex.getLocation());
    }

    @Test
    void failingOnStartDestroysTheMatch() {
        TestMatch match = openMatch(settings -> settings.countdownSeconds(0));
        match.throwOnStart = new IllegalStateException("boom");
        match.admit(List.of(player("Alex")));
        assertEquals(MatchPhase.DESTROYED, match.getPhase());
        assertFalse(api.events.contains("started"));
    }

    // Plugin jars built against a different version of a dependency fail with a LinkageError.
    @Test
    void anIncompatibleJarFailingOnStartStillDestroysTheMatch() {
        TestMatch match = openMatch(settings -> settings.countdownSeconds(0));
        match.throwOnStart = new NoSuchMethodError("built against another version");
        var alex = player("Alex");
        match.admit(List.of(alex));
        assertEquals(MatchPhase.DESTROYED, match.getPhase());
        assertNull(MatchCore.matchOf(alex));
    }

    @Test
    void anIncompatibleAddOnVetoesInsteadOfBreakingAdmission() {
        TestMatch match = openMatch(settings -> { });
        api.duringJoinAttempt = () -> {
            throw new NoSuchMethodError("add-on built against another version");
        };
        var alex = player("Alex");
        assertEquals(AdmissionResult.VETOED, match.admit(List.of(alex)));
        assertNull(MatchCore.matchOf(alex));
    }

    @Test
    void lateJoinersAreRefusedOnceStarting() {
        TestMatch match = openMatch(settings -> { });
        match.admit(List.of(player("Alex")));
        match.start();
        assertEquals(AdmissionResult.NOT_ACCEPTING, match.admit(List.of(player("Bea"))));
    }

    @Test
    void waitingMatchesRemindParticipantsEveryMinute() {
        TestMatch match = openMatch(settings -> settings.players(2, 4));
        var alex = player("Alex");
        match.admit(List.of(alex));
        drainMessages(alex);
        server.getScheduler().performTicks(20 * 60);
        assertEquals("§7Waiting to start. This match needs 2 players.", alex.nextMessage());
    }

    private static void drainMessages(PlayerMock player) {
        while (player.nextMessage() != null) {
        }
    }
}
