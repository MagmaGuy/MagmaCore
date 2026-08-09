package com.magmaguy.magmacore.scripting;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                "is_in_dungeon", "is_protected", "owned_by", "owners", "kinds_at", "has_kind"}) {
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
}
