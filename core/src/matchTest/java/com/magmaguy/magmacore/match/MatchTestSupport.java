package com.magmaguy.magmacore.match;

import com.magmaguy.magmacore.MagmaCore;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.lang.reflect.Field;
import java.util.function.Consumer;

abstract class MatchTestSupport {
    protected ServerMock server;
    protected PluginMock plugin;
    protected WorldMock overworld;
    protected WorldMock arena;
    protected RecordingApi api;

    @BeforeEach
    void startServer() {
        resetMagmaCoreSingleton();
        server = MockBukkit.mock();
        overworld = server.addSimpleWorld("world");
        arena = server.addSimpleWorld("arena");
        plugin = MockBukkit.createMockPlugin("MatchTest");
        MagmaCore.createInstance(plugin);
        MatchCore.enable(plugin);
        api = new RecordingApi();
    }

    @AfterEach
    void stopServer() {
        try {
            MatchCore.shutdown();
        } finally {
            try {
                MagmaCore.shutdown(plugin);
            } catch (Throwable ignored) {
                // Teardown must reach unmock even when a test broke MagmaCore state.
            }
            resetMagmaCoreSingleton();
            if (MockBukkit.isMocked()) MockBukkit.unmock();
        }
    }

    protected PlayerMock player(String name) {
        PlayerMock player = server.addPlayer(name);
        player.teleport(overworld.getSpawnLocation());
        return player;
    }

    protected static Location at(World world, double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    /** Region over the whole arena world, three second countdown, 1 to 4 players. */
    protected TestMatch match(Consumer<MatchSettings.Builder> customize) {
        MatchSettings.Builder builder = MatchSettings.builder()
                .space(MatchSpace.region(arena, location -> true))
                .start(at(arena, 0.5, 64, 0.5))
                .players(1, 4)
                .countdownSeconds(3)
                .api(api);
        customize.accept(builder);
        return new TestMatch(builder.build());
    }

    protected TestMatch openMatch(Consumer<MatchSettings.Builder> customize) {
        TestMatch match = match(customize);
        if (!match.open()) throw new IllegalStateException("open() was vetoed");
        return match;
    }

    private static void resetMagmaCoreSingleton() {
        try {
            Field instance = MagmaCore.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
