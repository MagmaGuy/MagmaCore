package com.magmaguy.easyminecraftgoals.v26.mind;

import com.magmaguy.magmacore.ai.MindPersistentState;
import com.magmaguy.magmacore.ai.MindProgram;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.debug.ServerDebugSubscribers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.damagesource.DamageSources;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NativeMindCarrierFactoryContractTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getLogger" -> Logger.getAnonymousLogger();
                    case "getName", "getVersion", "getBukkitVersion" -> "native-mind-test";
                    case "isPrimaryThread" -> true;
                    case "getRegistry" -> Proxy.newProxyInstance(
                            Registry.class.getClassLoader(),
                            new Class<?>[]{Registry.class},
                            (registryProxy, registryMethod, registryArguments) -> null);
                    default -> null;
                });
        Bukkit.setServer(server);
        CraftRegistry.setMinecraftRegistry(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }

    @Test
    void logicalSessionRetainsItsBodylessStateAcrossIdempotentPauseTransitions() {
        NativeMindSession session = new NativeMindSession(
                mock(NativeMindHost.class),
                UUID.randomUUID(),
                MindProgram.builder("fixture:paused", 1L).build(),
                MindPersistentState.empty(),
                new NativeMindServerBudget());

        session.setPaused(true);
        session.setPaused(true);
        assertTrue(session.inspect().paused());

        session.setPaused(false);
        session.setPaused(false);
        assertFalse(session.inspect().paused());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("carrierFamilies")
    void minecraftFactoryCreatesTheRegisteredCarrierClass(
            String family,
            EntityType<?> type,
            Class<? extends Entity> expectedClass) {
        ServerLevel level = mock(ServerLevel.class);
        CraftServer craftServer = mock(CraftServer.class);
        MinecraftServer minecraftServer = mock(MinecraftServer.class);
        when(level.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
        when(level.getCraftServer()).thenReturn(craftServer);
        when(level.getMinecraftWorld()).thenReturn(level);
        when(level.getServer()).thenReturn(minecraftServer);
        when(minecraftServer.debugSubscribers()).thenReturn(mock(ServerDebugSubscribers.class));
        when(level.damageSources()).thenReturn(mock(DamageSources.class));
        when(craftServer.getPluginManager()).thenReturn(mock(PluginManager.class));
        Entity carrier = type.create(level, EntitySpawnReason.COMMAND);

        assertInstanceOf(expectedClass, carrier, family);
        assertSame(type, carrier.getType(), family);
    }

    private static Stream<Arguments> carrierFamilies() {
        return Stream.of(
                Arguments.of("ambient", EntityTypes.BAT, Bat.class),
                Arguments.of("grounded hostile", EntityTypes.VINDICATOR, Vindicator.class),
                Arguments.of("amphibious", EntityTypes.DROWNED, Drowned.class),
                Arguments.of("aquatic", EntityTypes.SQUID, Squid.class),
                Arguments.of("flying", EntityTypes.PHANTOM, Phantom.class),
                Arguments.of("ranged", EntityTypes.BLAZE, Blaze.class),
                Arguments.of("non-pathfinder", EntityTypes.SLIME, Slime.class),
                Arguments.of("animal carrier", EntityTypes.LLAMA, Llama.class),
                Arguments.of("boss", EntityTypes.ENDER_DRAGON, EnderDragon.class));
    }
}
