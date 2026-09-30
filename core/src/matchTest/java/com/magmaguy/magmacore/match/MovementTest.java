package com.magmaguy.magmacore.match;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MovementTest extends MatchTestSupport {
    private TestMatch ongoing(Consumer<MatchSettings.Builder> customize, PlayerMock... players) {
        TestMatch match = openMatch(settings -> {
            settings.countdownSeconds(0).players(1, 4);
            customize.accept(settings);
        });
        match.admit(List.of(players));
        server.getScheduler().performTicks(1);
        return match;
    }

    @Test
    void participantsCannotTeleportOutOnTheirOwn() {
        var alex = player("Alex");
        ongoing(settings -> { }, alex);
        assertFalse(alex.teleport(overworld.getSpawnLocation()));
        assertEquals(arena, alex.getWorld());
    }

    @Test
    void enderPearlsInsideTheMatchAreBlocked() {
        var alex = player("Alex");
        ongoing(settings -> { }, alex);
        assertFalse(alex.teleport(at(arena, 5, 64, 5), PlayerTeleportEvent.TeleportCause.ENDER_PEARL));
    }

    @Test
    void moveWithinWorksInsideTheSameWorldOfAnOngoingMatch() {
        var alex = player("Alex");
        ongoing(settings -> { }, alex);
        assertTrue(MatchCore.moveWithin(alex, at(arena, 5.5, 64, 5.5), PlayerTeleportEvent.TeleportCause.PLUGIN));
        assertEquals(at(arena, 5.5, 64, 5.5), alex.getLocation());
    }

    @Test
    void moveWithinRefusesDestinationsOutsideTheSpace() {
        var alex = player("Alex");
        ongoing(settings -> settings.space(MatchSpace.region(arena, new BoundingBox(-50, 0, -50, 50, 200, 50))), alex);
        assertFalse(MatchCore.moveWithin(alex, at(arena, 500, 64, 500), PlayerTeleportEvent.TeleportCause.PLUGIN));
    }

    @Test
    void moveWithinRefusesOtherWorlds() {
        var alex = player("Alex");
        ongoing(settings -> { }, alex);
        assertFalse(MatchCore.moveWithin(alex, overworld.getSpawnLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN));
    }

    @Test
    void moveOutCountsAsQuittingOnlyWhenTheTeleportCompletes() {
        var alex = player("Alex");
        var bea = player("Bea");
        TestMatch match = ongoing(settings -> { }, alex, bea);
        Listener blocker = new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST)
            public void block(PlayerTeleportEvent event) {
                if (event.getPlayer().equals(alex)) event.setCancelled(true);
            }
        };
        server.getPluginManager().registerEvents(blocker, plugin);
        assertFalse(MatchCore.moveOut(alex, overworld.getSpawnLocation(), PlayerTeleportEvent.TeleportCause.COMMAND));
        assertSame(match, MatchCore.matchOf(alex));
        HandlerList.unregisterAll(blocker);

        assertTrue(MatchCore.moveOut(alex, overworld.getSpawnLocation(), PlayerTeleportEvent.TeleportCause.COMMAND));
        assertTrue(match.hooks.contains("onLeave:Alex:QUIT"));
        assertEquals(overworld, alex.getWorld());
    }

    @Test
    void moveParticipantCrossesWorldsInsideTheMatch() {
        var alex = player("Alex");
        WorldMock second = server.addSimpleWorld("arena_2");
        TemporaryWorlds space = MatchSpace.temporaryWorlds(arena);
        TestMatch match = ongoing(settings -> settings.space(space), alex);
        space.addWorld(second);
        assertTrue(match.moveParticipant(alex, at(second, 0.5, 64, 0.5)));
        assertEquals(second, alex.getWorld());
    }

    @Test
    void staffPermissionAllowsCommandTeleportsInsideTheMatchOnly() {
        var alex = player("Alex");
        ongoing(settings -> settings.withinTeleportPermission("test.within"), alex);
        alex.addAttachment(plugin, "test.within", true);
        assertTrue(alex.teleport(at(arena, 8.5, 64, 8.5), PlayerTeleportEvent.TeleportCause.COMMAND));
        assertFalse(alex.teleport(at(arena, 9.5, 64, 9.5), PlayerTeleportEvent.TeleportCause.ENDER_PEARL));
        assertFalse(alex.teleport(overworld.getSpawnLocation(), PlayerTeleportEvent.TeleportCause.COMMAND));
    }

    @Test
    void waitingParticipantTeleportingElsewhereIsRemoved() {
        TestMatch match = openMatch(settings -> settings.players(2, 4));
        var alex = player("Alex");
        match.admit(List.of(alex, player("Bea")));
        assertTrue(alex.teleport(at(overworld, 40, 64, 40)));
        assertNull(MatchCore.matchOf(alex));
    }

    @Test
    void strangersCannotTeleportIntoTemporaryWorldsEvenWhileWaiting() {
        openMatch(settings -> settings.space(MatchSpace.temporaryWorlds(arena)));
        var stranger = player("Stranger");
        assertFalse(stranger.teleport(at(arena, 0, 64, 0)));
    }

    @Test
    void bypassHoldersMayTeleportIn() {
        openMatch(settings -> settings.space(MatchSpace.temporaryWorlds(arena)).bypassPermission("test.bypass"));
        var admin = player("Admin");
        admin.addAttachment(plugin, "test.bypass", true);
        assertTrue(admin.teleport(at(arena, 0, 64, 0)));
    }

    @Test
    void regionDoorsOnlyCloseOnceTheMatchIsRunning() {
        MatchSpace box = MatchSpace.region(overworld, new BoundingBox(100, 0, 100, 120, 200, 120));
        TestMatch match = openMatch(settings -> settings.space(box).start(at(overworld, 110.5, 64, 110.5)).players(2, 4));
        var stranger = player("Stranger");
        assertTrue(stranger.teleport(at(overworld, 105, 64, 105)));
        assertTrue(stranger.teleport(overworld.getSpawnLocation()));
        match.admit(List.of(player("Alex"), player("Bea")));
        match.start();
        server.getScheduler().performTicks(41);
        assertEquals(MatchPhase.ONGOING, match.getPhase());
        assertFalse(stranger.teleport(at(overworld, 105, 64, 105)));
    }

    @Test
    void anAuthorizedMoveDoesNotAuthorizeSomeoneElse() {
        var alex = player("Alex");
        var bea = player("Bea");
        TestMatch match = ongoing(settings -> { }, alex, bea);
        AtomicBoolean beaEscaped = new AtomicBoolean();
        Listener sneaky = new Listener() {
            @EventHandler
            public void onMove(PlayerTeleportEvent event) {
                if (event.getPlayer().equals(alex)) beaEscaped.set(bea.teleport(overworld.getSpawnLocation()));
            }
        };
        server.getPluginManager().registerEvents(sneaky, plugin);
        assertTrue(match.moveParticipant(alex, at(arena, 3.5, 64, 3.5)));
        assertFalse(beaEscaped.get());
        assertEquals(arena, bea.getWorld());
    }
}
