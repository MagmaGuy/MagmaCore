package com.magmaguy.magmacore.match;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LeaveTest extends MatchTestSupport {
    @Test
    void onLeaveRunsWhileStillAParticipantThenThePlayerIsRestored() {
        TestMatch match = openMatch(settings -> settings.players(1, 4).gameMode(GameMode.ADVENTURE));
        var alex = player("Alex");
        var bea = player("Bea");
        alex.setGameMode(GameMode.SURVIVAL);
        Location before = alex.getLocation().clone();
        match.admit(List.of(alex, bea));
        server.getScheduler().performTicks(1);

        assertTrue(match.leave(alex, LeaveReason.QUIT));

        assertEquals(Boolean.TRUE, match.participantDuringOnLeave);
        assertTrue(api.events.contains("left:Alex:QUIT"));
        assertEquals(before, alex.getLocation());
        assertEquals(GameMode.SURVIVAL, alex.getGameMode());
        assertTrue(alex.getMetadata(MatchCore.MARKER_KEY).isEmpty());
        assertNull(MatchCore.matchOf(alex));
        assertTrue(match.hooks.contains("onLeave:Alex:QUIT"));
        assertEquals(MatchPhase.WAITING, match.getPhase(), "Bea is still in");
    }

    @Test
    void exitDestinationHookWins() {
        TestMatch match = openMatch(settings -> settings.players(1, 4));
        match.exit = participant -> at(overworld, 100.5, 70, 100.5);
        var alex = player("Alex");
        match.admit(List.of(alex, player("Bea")));
        server.getScheduler().performTicks(1);
        match.leave(alex, LeaveReason.QUIT);
        assertEquals(at(overworld, 100.5, 70, 100.5), alex.getLocation());
    }

    @Test
    void settingsExitBeatsThePreviousLocation() {
        TestMatch match = openMatch(settings -> settings.players(1, 4).exit(at(overworld, 50.5, 65, 50.5)));
        var alex = player("Alex");
        match.admit(List.of(alex, player("Bea")));
        server.getScheduler().performTicks(1);
        match.leave(alex, LeaveReason.QUIT);
        assertEquals(at(overworld, 50.5, 65, 50.5), alex.getLocation());
    }

    @Test
    void lastActivePlayerLeavingEndsTheMatchInDefeat() {
        TestMatch match = openMatch(settings -> { });
        var alex = player("Alex");
        match.admit(List.of(alex));
        server.getScheduler().performTicks(1);
        match.leave(alex, LeaveReason.QUIT);
        assertEquals(MatchOutcome.DEFEAT, match.getOutcome());
        assertEquals(MatchPhase.DESTROYED, match.getPhase());
    }

    @Test
    void disconnectLeavesWithDisconnect() {
        TestMatch match = openMatch(settings -> settings.players(1, 4));
        var alex = player("Alex");
        match.admit(List.of(alex, player("Bea")));
        alex.disconnect();
        assertTrue(match.hooks.contains("onLeave:Alex:DISCONNECT"));
        assertNull(MatchCore.matchOf(alex));
    }

    @Test
    void leavingBeforeEntryCancelsTheEntryTeleport() {
        TestMatch match = openMatch(settings -> settings.players(1, 4));
        var alex = player("Alex");
        match.admit(List.of(alex, player("Bea")));
        match.leave(alex, LeaveReason.QUIT);
        server.getScheduler().performTicks(2);
        assertEquals(overworld, alex.getWorld());
    }

    @Test
    void leavingTwiceIsANoOp() {
        TestMatch match = openMatch(settings -> settings.players(1, 4));
        var alex = player("Alex");
        match.admit(List.of(alex, player("Bea")));
        assertTrue(match.leave(alex, LeaveReason.QUIT));
        assertFalse(match.leave(alex, LeaveReason.QUIT));
    }

    @Test
    void aFailedEntryTeleportRemovesThePlayer() {
        TestMatch match = openMatch(settings -> settings.players(1, 4));
        match.entry = participant -> null;
        var alex = player("Alex");
        match.admit(List.of(alex, player("Bea")));
        server.getScheduler().performTicks(1);
        assertNull(MatchCore.matchOf(alex));
        assertTrue(match.hooks.contains("onLeave:Alex:QUIT"));
    }
}
