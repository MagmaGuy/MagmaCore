package com.magmaguy.magmacore.match;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Hooks a subclass needs when its own state decides admission, destinations and teardown. */
class SubclassHooksTest extends MatchTestSupport {
    private TestMatch ongoing(Consumer<MatchSettings.Builder> customize, PlayerMock... players) {
        TestMatch match = openMatch(settings -> {
            settings.countdownSeconds(0).players(1, 4);
            customize.accept(settings);
        });
        match.admit(List.of(players));
        server.getScheduler().performTicks(1);
        arena.loadChunk(0, 0);
        return match;
    }

    @Test
    void aMatchThatStopsAcceptingRefusesWithTheNotAcceptingMessage() {
        TestMatch match = openMatch(settings -> { });
        match.refuseAll = true;
        var alex = player("Alex");
        while (alex.nextMessage() != null) {
        }
        assertEquals(AdmissionResult.NOT_ACCEPTING, match.admit(List.of(alex)));
        assertEquals("§cThis match is not accepting players right now.", alex.nextMessage());
    }

    @Test
    void theStartDestinationHookDrivesTheStartAndTheRescue() {
        TestMatch match = openMatch(settings -> settings.start(null));
        match.startOverride = at(arena, 40.5, 64, 40.5);
        var alex = player("Alex");
        match.admit(List.of(alex));
        server.getScheduler().performTicks(1);
        assertEquals(at(arena, 40.5, 64, 40.5), alex.getLocation(), "entry defaults to the start");
        match.start();
        server.getScheduler().performTicks(41);
        alex.setLocation(at(arena, 40.5, arena.getMinHeight() - 5, 40.5));
        server.getScheduler().performTicks(1);
        assertEquals(at(arena, 40.5, 64, 40.5), alex.getLocation());
    }

    @Test
    void spectatingAndRevivingAreReported() {
        var alex = player("Alex");
        var bea = player("Bea");
        TestMatch match = ongoing(settings -> settings.death(DeathPolicy.spectateAndRevive(3)), alex, bea);
        alex.setLocation(at(arena, 10.5, 64, 10.5));
        server.getPluginManager().callEvent(new EntityDamageEvent(alex, EntityDamageEvent.DamageCause.CUSTOM, 1000));
        assertTrue(match.hooks.contains("onSpectating:Alex"));

        Block banner = arena.getBlockAt(10, 64, 10);
        assertTrue(match.releaseBanner(alex, true));
        assertTrue(match.hooks.contains("onRevive:Alex"));
        assertNotEquals(Material.RED_BANNER, banner.getType());
        assertEquals(GameMode.SURVIVAL, alex.getGameMode());
    }

    @Test
    void releasingABannerWithoutRevivingLeavesTheSpectator() {
        var alex = player("Alex");
        var bea = player("Bea");
        TestMatch match = ongoing(settings -> settings.death(DeathPolicy.spectateAndRevive(3)), alex, bea);
        alex.setLocation(at(arena, 10.5, 64, 10.5));
        server.getPluginManager().callEvent(new EntityDamageEvent(alex, EntityDamageEvent.DamageCause.CUSTOM, 1000));
        assertTrue(match.releaseBanner(alex, false));
        assertEquals(MatchRole.SPECTATOR, match.participant(alex).getRole());
        assertNotEquals(Material.RED_BANNER, arena.getBlockAt(10, 64, 10).getType());
        assertFalse(match.releaseBanner(alex, false), "nothing left to release");
    }

    @Test
    void aSubclassCanReportADeathItself() {
        var alex = player("Alex");
        var bea = player("Bea");
        TestMatch match = ongoing(settings -> { }, alex, bea);
        match.reportDeath(alex);
        assertTrue(match.hooks.containsAll(List.of("onDeath:Alex", "onLeave:Alex:DIED")));
    }

    @Test
    void withoutDestroyAfterEndTheSubclassDecidesWhenToDestroy() {
        var alex = player("Alex");
        TestMatch match = ongoing(settings -> settings.destroyAfterEnd(false), alex);
        match.end(MatchOutcome.VICTORY);
        server.getScheduler().performTicks(200);
        assertEquals(MatchPhase.ENDED, match.getPhase());
        assertSame(match, MatchCore.matchOf(alex));
        match.leave(alex, LeaveReason.QUIT);
        assertEquals(MatchPhase.ENDED, match.getPhase(), "the last player leaving an ended match changes nothing");
    }
}
