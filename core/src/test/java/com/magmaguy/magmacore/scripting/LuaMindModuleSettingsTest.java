package com.magmaguy.magmacore.scripting;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
        assertEquals(List.of("blocks", "effect", "none", "radius", "speed", "wild"),
                List.copyOf(drift.settings().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> drift.settings().put("radius", 9D));
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
        assertRejected("settings = { blocks = { 1, 2 } }", "blocks");
        assertRejected("settings = { blocks = { \"a\", x = \"b\" } }", "blocks");
        assertRejected("settings = { 8 }", "entry 1");
        assertRejected("settings = 5", "table");
        assertRejected("settings = { speed = 0/0 }", "speed");
    }

    @Test
    void listsAreReadWithoutRunningTheirMetamethods() {
        LuaMindModuleDescriptor hostile = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                LuaMindModuleRegistry.inspectModule("hostile.lua", """
                        local forever = { __len = function() while true do end end }
                        return ai.module { id = "fixture:hostile", revision = 1,
                          dependencies = setmetatable({}, forever),
                          settings = { blocks = setmetatable({ "A" }, forever) } }
                        """));
        assertEquals(List.of(), hostile.dependencies());
        assertEquals(List.of("A"), hostile.settings().get("blocks"));
    }

    @Test
    void aModuleWithoutSettingsHasNone() {
        assertEquals(Map.of(), LuaMindModuleRegistry.inspectModule("plain.lua", """
                return ai.module { id = "fixture:plain", revision = 1 }
                """).settings());
    }

    private static void assertRejected(String settings, String expectedInMessage) {
        IllegalArgumentException rejection = assertThrows(IllegalArgumentException.class, () ->
                LuaMindModuleRegistry.inspectModule("bad.lua",
                        "return ai.module { id = \"fixture:bad\", revision = 1, " + settings + " }"));
        assertTrue(rejection.getMessage().contains(expectedInMessage), rejection.getMessage());
    }
}
