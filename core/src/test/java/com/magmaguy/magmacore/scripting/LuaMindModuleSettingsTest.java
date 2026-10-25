package com.magmaguy.magmacore.scripting;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaMindModuleSettingsTest {
    private static final String DRIFT = """
            return ai.module {
              id = "fixture:drift", revision = 1,
              settings = { radius = 8, speed = 0.06, wild = true, effect = "SLOWNESS",
                           blocks = { "CAMPFIRE", "SOUL_CAMPFIRE" }, none = {} },
              behaviors = { ai.behavior { id = "fixture:drift", tick = function(c) end } }
            }
            """;

    @Test
    void aModuleDeclaresTypedSettingDefaults() {
        LuaMindModuleDescriptor drift = LuaMindModuleRegistry.inspectModule("drift.lua", DRIFT);
        assertEquals("fixture:drift", drift.identifier());
        assertEquals(Map.of("radius", 8D, "speed", 0.06D, "wild", true, "effect", "SLOWNESS",
                "blocks", List.of("CAMPFIRE", "SOUL_CAMPFIRE"), "none", List.of()), drift.settings());
    }

    @Test
    void registeredModulesKeepTheirSettings() {
        try (LuaMindModuleRegistry registry = new LuaMindModuleRegistry()) {
            LuaMindModuleDescriptor registered = registry.openOwner("fixture").registerModule("drift.lua", DRIFT);
            assertEquals(8D, registered.settings().get("radius"));
        }
    }

    @Test
    void inspectingDoesNotRegister() {
        try (LuaMindModuleRegistry registry = new LuaMindModuleRegistry()) {
            LuaMindModuleRegistry.OwnerScope owner = registry.openOwner("fixture");
            LuaMindModuleRegistry.inspectModule("drift.lua", DRIFT);
            assertTrue(owner.module("fixture:drift").isEmpty());
        }
    }

    @Test
    void settingsMustBePlainValues() {
        IllegalArgumentException function = assertThrows(IllegalArgumentException.class, () ->
                LuaMindModuleRegistry.inspectModule("bad.lua", """
                        return ai.module { id = "fixture:bad", revision = 1, settings = { pick = function() end } }
                        """));
        assertTrue(function.getMessage().contains("pick"), function.getMessage());
        assertThrows(IllegalArgumentException.class, () -> LuaMindModuleRegistry.inspectModule("bad.lua", """
                return ai.module { id = "fixture:bad", revision = 1, settings = { blocks = { 1, 2 } } }
                """));
    }

    @Test
    void aModuleWithoutSettingsHasNone() {
        assertEquals(Map.of(), LuaMindModuleRegistry.inspectModule("plain.lua", """
                return ai.module { id = "fixture:plain", revision = 1 }
                """).settings());
    }
}
