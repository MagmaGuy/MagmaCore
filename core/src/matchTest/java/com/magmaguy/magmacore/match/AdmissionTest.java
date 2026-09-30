package com.magmaguy.magmacore.match;

import org.bukkit.GameMode;
import org.bukkit.metadata.FixedMetadataValue;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdmissionTest extends MatchTestSupport {
    @Test
    void admittedPlayerEntersAtTheStartOnTheNextTick() {
        TestMatch match = openMatch(settings -> { });
        var alex = player("Alex");

        assertEquals(AdmissionResult.ADMITTED, match.admit(List.of(alex)));
        assertEquals(overworld, alex.getWorld(), "entry waits one tick");
        assertSame(match, MatchCore.matchOf(alex));

        server.getScheduler().performTicks(1);
        assertEquals(at(arena, 0.5, 64, 0.5), alex.getLocation());
        assertTrue(match.participant(alex).hasEntered());
        assertEquals(List.of("instantiateAttempt", "joinAttempt:Alex", "joined:Alex"), api.events);
        assertEquals(List.of("reserveAdmission", "onJoin:Alex"), match.hooks);
    }

    @Test
    void lobbyIsUsedWhileWaiting() {
        TestMatch match = openMatch(settings -> settings.lobby(at(arena, 10.5, 64, 10.5)));
        var alex = player("Alex");
        match.admit(List.of(alex));
        server.getScheduler().performTicks(1);
        assertEquals(at(arena, 10.5, 64, 10.5), alex.getLocation());
    }

    @Test
    void entryDestinationHookWins() {
        TestMatch match = openMatch(settings -> { });
        match.entry = participant -> at(arena, 30.5, 70, 30.5);
        var alex = player("Alex");
        match.admit(List.of(alex));
        server.getScheduler().performTicks(1);
        assertEquals(at(arena, 30.5, 70, 30.5), alex.getLocation());
    }

    @Test
    void admissionMarksThePlayerForEveryPluginToSee() {
        TestMatch match = openMatch(settings -> { });
        var alex = player("Alex");
        match.admit(List.of(alex));
        assertEquals("MatchTest:" + match.getRuntimeId(),
                alex.getMetadata(MatchCore.MARKER_KEY).getFirst().asString());
    }

    @Test
    void playersMarkedByAnotherPluginAreRefused() {
        TestMatch match = openMatch(settings -> { });
        var alex = player("Alex");
        var other = MockBukkit.createMockPlugin("OtherPlugin");
        alex.setMetadata(MatchCore.MARKER_KEY, new FixedMetadataValue(other, "OtherPlugin:x"));
        assertEquals(AdmissionResult.ALREADY_IN_MATCH, match.admit(List.of(alex)));
        assertNull(MatchCore.matchOf(alex));
    }

    @Test
    void unopenedMatchesRefuseEveryone() {
        TestMatch match = match(settings -> { });
        assertEquals(AdmissionResult.NOT_ACCEPTING, match.admit(List.of(player("Alex"))));
    }

    @Test
    void fullMatchRefuses() {
        TestMatch match = openMatch(settings -> settings.players(1, 1));
        match.admit(List.of(player("Alex")));
        assertEquals(AdmissionResult.FULL, match.admit(List.of(player("Bea"))));
    }

    @Test
    void permissionIsRequiredWhenSet() {
        TestMatch match = openMatch(settings -> settings.permission("test.join"));
        var alex = player("Alex");
        assertEquals(AdmissionResult.NO_PERMISSION, match.admit(List.of(alex)));
        alex.addAttachment(plugin, "test.join", true);
        assertEquals(AdmissionResult.ADMITTED, match.admit(List.of(alex)));
    }

    @Test
    void refusedAdmissionSendsTheConfiguredMessageToTheFirstPlayer() {
        TestMatch match = openMatch(settings -> settings.players(1, 1));
        match.admit(List.of(player("Alex")));
        var bea = player("Bea");
        match.admit(List.of(bea));
        assertEquals("§cThis match is full.", bea.nextMessage());
    }

    @Test
    void gameModeFromSettingsIsAppliedOnEntryAndRecorded() {
        TestMatch match = openMatch(settings -> settings.gameMode(GameMode.ADVENTURE));
        var alex = player("Alex");
        alex.setGameMode(GameMode.SURVIVAL);
        match.admit(List.of(alex));
        assertEquals(GameMode.ADVENTURE, alex.getGameMode());
        assertEquals(GameMode.SURVIVAL, match.participant(alex).getPreviousGameMode());
    }
}
