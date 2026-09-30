package com.magmaguy.magmacore.match;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class WatchdogTest extends MatchTestSupport {
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
    void playersBelowTheWorldAreRescuedToTheStartWithoutASafeSpot() {
        var alex = player("Alex");
        ongoing(settings -> { }, alex);
        alex.setFallDistance(30);
        alex.setLocation(at(arena, 0.5, arena.getMinHeight() - 5, 0.5));
        server.getScheduler().performTicks(1);
        assertEquals(at(arena, 0.5, 64, 0.5), alex.getLocation());
        assertEquals(0f, alex.getFallDistance());
    }

    @Test
    void rescueReturnsToTheLastSolidBlock() {
        var alex = player("Alex");
        ongoing(settings -> { }, alex);
        alex.setOnGround(true);
        alex.setLocation(at(arena, 12.5, 70, 12.5));
        server.getScheduler().performTicks(1);
        alex.setOnGround(false);
        alex.setLocation(at(arena, 12.5, arena.getMinHeight() - 5, 12.5));
        server.getScheduler().performTicks(1);
        assertEquals(at(arena, 12.5, 70, 12.5), alex.getLocation());
    }

    @Test
    void afterThreeFailedRescuesTheStartIsUsed() {
        var alex = player("Alex");
        ongoing(settings -> { }, alex);
        alex.setOnGround(true);
        alex.setLocation(at(arena, 12.5, 70, 12.5));
        server.getScheduler().performTicks(1);
        alex.setOnGround(false);
        for (int rescue = 1; rescue <= 3; rescue++) {
            alex.setLocation(at(arena, 12.5, arena.getMinHeight() - 5, 12.5));
            server.getScheduler().performTicks(1);
            assertEquals(at(arena, 12.5, 70, 12.5), alex.getLocation(), "rescue " + rescue);
        }
        alex.setLocation(at(arena, 12.5, arena.getMinHeight() - 5, 12.5));
        server.getScheduler().performTicks(1);
        assertEquals(at(arena, 0.5, 64, 0.5), alex.getLocation());
    }

    @Test
    void playersOutsideARegionAreRescued() {
        var alex = player("Alex");
        ongoing(settings -> settings.space(MatchSpace.region(arena, new BoundingBox(-50, 0, -50, 50, 200, 50))), alex);
        alex.setLocation(at(arena, 300, 64, 300));
        server.getScheduler().performTicks(1);
        assertEquals(at(arena, 0.5, 64, 0.5), alex.getLocation());
    }

    @Test
    void intrudersAreEjectedWhileTheMatchRuns() {
        var alex = player("Alex");
        ongoing(settings -> settings.exit(at(overworld, 7.5, 64, 7.5)), alex);
        var stranger = player("Stranger");
        stranger.setLocation(at(arena, 4, 64, 4));
        server.getScheduler().performTicks(1);
        assertEquals(at(overworld, 7.5, 64, 7.5), stranger.getLocation());
    }

    @Test
    void bypassHoldersAreNotEjected() {
        var alex = player("Alex");
        ongoing(settings -> settings.bypassPermission("test.bypass"), alex);
        var admin = player("Admin");
        admin.addAttachment(plugin, "test.bypass", true);
        admin.setLocation(at(arena, 4, 64, 4));
        server.getScheduler().performTicks(1);
        assertEquals(arena, admin.getWorld());
    }

    @Test
    void intrudersAreLeftAloneWhileWaiting() {
        openMatch(settings -> settings.players(2, 4)).admit(List.of(player("Alex")));
        var stranger = player("Stranger");
        stranger.setLocation(at(arena, 4, 64, 4));
        server.getScheduler().performTicks(1);
        assertEquals(arena, stranger.getWorld());
    }
}
