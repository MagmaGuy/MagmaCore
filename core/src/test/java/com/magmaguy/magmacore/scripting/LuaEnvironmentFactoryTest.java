package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.location.LocationQueryRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Pins the {@code em.location} Lua surface. This table was added in commit 133b429 and then
 * silently lost by a stale-branch commit (9ccd418) that reverted LuaEnvironmentFactory — which
 * disabled every shipped script calling {@code em.location.is_protected} (Craftenmine item skills,
 * FMM storage/pickupable props) with "You tried to access a property on a nil or invalid value".
 * If any of these assertions fail, scripts in shipped content packs break in the field.
 */
class LuaEnvironmentFactoryTest {

    @Test
    void emLocationTableExposesAllQueryFunctions() {
        Globals globals = LuaEnvironmentFactory.createGlobals();
        LuaValue location = globals.get("em").get("location");

        assertTrue(location.istable(), "em.location must exist — shipped pack scripts index it directly");
        for (String function : new String[]{
                "is_in_dungeon", "is_protected", "can_build", "owned_by", "owners", "kinds_at", "has_kind"}) {
            assertTrue(location.get(function).isfunction(),
                    "em.location." + function + " must be a function");
        }
    }

    @Test
    void scriptShapedCallSurvivesLocationTableWithoutWorld() {
        // Mirrors the failing Craftenmine hook shape: build a location table and pass it to
        // is_protected. Without a resolvable world the query must return false, not error out
        // (an error would disable the script for the entity).
        Globals globals = LuaEnvironmentFactory.createGlobals();
        LuaValue result = globals.load(
                "local loc = em.create_location(1, 64, 2)\n" +
                        "if em.location.is_protected(loc) then return true end\n" +
                        "if em.location.is_in_dungeon(loc) then return true end\n" +
                        "return false"
        ).call();

        assertFalse(result.toboolean());
    }

    @Test
    void locationQueriesTolerateNilAndNonTableArguments() {
        Globals globals = LuaEnvironmentFactory.createGlobals();
        LuaValue location = globals.get("em").get("location");

        assertFalse(location.get("is_protected").call(LuaValue.NIL).toboolean());
        assertFalse(location.get("is_in_dungeon").call(LuaValue.valueOf("not a table")).toboolean());
        assertFalse(location.get("owned_by").call(LuaValue.NIL, LuaValue.valueOf("ns")).toboolean());
        assertFalse(location.get("has_kind").call(LuaValue.NIL, LuaValue.valueOf("kind")).toboolean());
        assertEquals(0, location.get("owners").call(LuaValue.NIL).checktable().length());
        assertEquals(0, location.get("kinds_at").call(LuaValue.NIL).checktable().length());
    }

    @Test
    void canBuildAsksTheRegistryAboutThatPlayer() {
        UUID ownerId = UUID.randomUUID(), strangerId = UUID.randomUUID();
        Player owner = mock(Player.class), stranger = mock(Player.class);
        World world = mock(World.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<LocationQueryRegistry> registry = mockStatic(LocationQueryRegistry.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(owner);
            bukkit.when(() -> Bukkit.getPlayer(strangerId)).thenReturn(stranger);
            registry.when(() -> LocationQueryRegistry.canBuild(eq(owner), any(Location.class))).thenReturn(true);
            registry.when(() -> LocationQueryRegistry.canBuild(eq(stranger), any(Location.class))).thenReturn(false);

            Globals globals = LuaEnvironmentFactory.createGlobals();
            LuaValue canBuild = globals.load(
                    "return function(id)\n" +
                            "  return em.location.can_build({ uuid = id }, em.create_location(1, 64, 2, \"world\"))\n" +
                            "end").call();

            assertTrue(canBuild.call(LuaValue.valueOf(ownerId.toString())).toboolean());
            assertFalse(canBuild.call(LuaValue.valueOf(strangerId.toString())).toboolean());
            // An offline player, a missing uuid or a location without a world fails closed.
            assertFalse(canBuild.call(LuaValue.valueOf(UUID.randomUUID().toString())).toboolean());
            assertFalse(canBuild.call(LuaValue.valueOf("not a uuid")).toboolean());
            assertFalse(globals.load("return em.location.can_build(nil, em.create_location(1, 64, 2, \"world\"))")
                    .call().toboolean());
            assertFalse(globals.load("return em.location.can_build({ uuid = \"" + ownerId + "\" }, em.create_location(1, 64, 2))")
                    .call().toboolean());
        }
    }
}
