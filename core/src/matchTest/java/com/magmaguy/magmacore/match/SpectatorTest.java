package com.magmaguy.magmacore.match;

import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpectatorTest extends MatchTestSupport {
    @Test
    void spectatorsNeedASpectatableMatch() {
        TestMatch match = openMatch(settings -> { });
        var sam = player("Sam");
        assertEquals(AdmissionResult.NOT_ACCEPTING, match.admitSpectator(sam));
        assertNull(MatchCore.matchOf(sam));
    }

    @Test
    void spectatorsWatchFromTheStartInSpectatorMode() {
        TestMatch match = openMatch(settings -> settings.spectatable(true));
        var sam = player("Sam");
        assertEquals(AdmissionResult.ADMITTED, match.admitSpectator(sam));
        assertEquals(GameMode.SPECTATOR, sam.getGameMode());
        assertEquals(at(arena, 0.5, 64, 0.5), sam.getLocation());
        assertEquals(MatchRole.SPECTATOR, match.participant(sam).getRole());
        assertTrue(api.events.contains("joined:Sam"));
        assertTrue(match.hooks.contains("onJoin:Sam"));
    }

    @Test
    void aVetoedSpectatorIsNotAdmitted() {
        TestMatch match = openMatch(settings -> settings.spectatable(true));
        api.vetoJoin.add("Sam");
        var sam = player("Sam");
        assertEquals(AdmissionResult.VETOED, match.admitSpectator(sam));
        assertEquals(GameMode.SURVIVAL, sam.getGameMode());
    }

    @Test
    void spectatorsDoNotTakePlayerSlots() {
        TestMatch match = openMatch(settings -> settings.spectatable(true).players(1, 1));
        match.admitSpectator(player("Sam"));
        assertEquals(AdmissionResult.ADMITTED, match.admit(List.of(player("Alex"))));
    }

    @Test
    void spectatorsLeavingGetTheirGameModeBack() {
        TestMatch match = openMatch(settings -> settings.spectatable(true).players(1, 4));
        match.admit(List.of(player("Alex")));
        var sam = player("Sam");
        match.admitSpectator(sam);
        match.leave(sam, LeaveReason.QUIT);
        assertEquals(GameMode.SURVIVAL, sam.getGameMode());
        assertEquals(overworld, sam.getWorld());
    }

    @Test
    void spectatorsDoNotKeepTheMatchAlive() {
        TestMatch match = openMatch(settings -> settings.spectatable(true));
        var alex = player("Alex");
        var sam = player("Sam");
        match.admit(List.of(alex));
        match.admitSpectator(sam);
        match.leave(alex, LeaveReason.QUIT);
        assertEquals(MatchPhase.DESTROYED, match.getPhase());
        assertTrue(match.hooks.contains("onLeave:Sam:MATCH_ENDED"));
    }

    @Test
    void spectatorCamerasOnOutsidersAreReleased() {
        TestMatch match = openMatch(settings -> settings.spectatable(true));
        var alex = player("Alex");
        match.admit(List.of(alex));
        server.getScheduler().performTicks(1);
        var sam = player("Sam");
        match.admitSpectator(sam);
        var outsider = player("Outsider");

        sam.setSpectatorTarget(alex);
        server.getScheduler().performTicks(1);
        assertEquals(alex, sam.getSpectatorTarget(), "watching a participant is fine");

        sam.setSpectatorTarget(outsider);
        server.getScheduler().performTicks(1);
        assertNull(sam.getSpectatorTarget());
    }
}
