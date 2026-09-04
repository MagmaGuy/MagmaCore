package com.magmaguy.magmacore.scripting;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaTable;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptInstancePauseTest {
    private static final ScriptHook QUERY = new ScriptHook("query");

    @Test
    void pausedInstanceRejectsHooksWithoutDiscardingItsLuaRuntime() {
        ScriptDefinition definition = new ScriptDefinition(
                "pause-fixture.lua",
                null,
                "return { query = function() return 'ran' end }",
                0,
                Set.of(QUERY));
        ScriptInstance instance = new ScriptInstance(definition, new FixtureEntity());

        assertEquals("ran", instance.handleQuery(QUERY, null, null, null)
                .stringValue().orElseThrow());

        instance.setPaused(true);
        assertTrue(instance.isPaused());
        assertEquals(ScriptQueryResult.Kind.UNHANDLED,
                instance.handleQuery(QUERY, null, null, null).kind());

        instance.setPaused(false);
        assertFalse(instance.isPaused());
        assertEquals("ran", instance.handleQuery(QUERY, null, null, null)
                .stringValue().orElseThrow());
    }

    private static final class FixtureEntity extends ScriptableEntity {
        @Override
        public LuaTable buildContextTable(ScriptInstance instance) {
            return new LuaTable();
        }

        @Override
        public String getContextKey() {
            return "fixture";
        }

        @Override
        public Set<ScriptHook> getSupportedHooks() {
            return Set.of(QUERY);
        }

        @Override
        public Entity getBukkitEntity() {
            return null;
        }

        @Override
        public Location getLocation() {
            return null;
        }

        @Override
        public boolean isScriptOwnerActive() {
            return true;
        }
    }
}
