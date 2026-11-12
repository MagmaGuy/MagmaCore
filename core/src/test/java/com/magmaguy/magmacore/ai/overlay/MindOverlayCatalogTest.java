package com.magmaguy.magmacore.ai.overlay;

import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.scripting.LuaMindContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MindOverlayCatalogTest {
    private final List<String> warnings = new ArrayList<>();

    private static String module(String name, int priority, String extra) {
        return """
                return ai.module {
                  id = "test:mob/%s", revision = 1, %s
                  behaviors = { ai.behavior { id = "test:mob/%s", priority = %d,
                    controls = { ai.controls.move }, tick = function(c) end } }
                }
                """.formatted(name, extra, name, priority);
    }

    @Test
    void aFileIsABehaviorNamedForItsFile() {
        try (MindOverlayCatalog catalog = MindOverlayCatalog.of("test",
                Map.of("coward", module("coward", 5, ""), "guard", module("guard", 3, "")), warnings::add)) {
            assertEquals(List.of("coward", "guard"), catalog.behaviors());
            assertTrue(warnings.isEmpty(), warnings.toString());
        }
    }

    @Test
    void aFileWhoseIdDoesNotMatchItsNameIsLeftOutWithTheReason() {
        try (MindOverlayCatalog catalog = MindOverlayCatalog.of("test",
                Map.of("coward", module("runaway", 5, "")), warnings::add)) {
            assertEquals(List.of(), catalog.behaviors());
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("coward.lua") && warnings.get(0).contains("test:mob/coward"),
                    warnings.get(0));
        }
    }

    @Test
    void aFileThatDoesNotCompileIsLeftOutAndTheOthersLoad() {
        try (MindOverlayCatalog catalog = MindOverlayCatalog.of("test",
                Map.of("broken", "return ai.module { id = ", "guard", module("guard", 3, "")), warnings::add)) {
            assertEquals(List.of("guard"), catalog.behaviors());
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).startsWith("Mob behavior broken.lua was left out"), warnings.get(0));
        }
    }

    @Test
    void aBehaviorLoadsAfterTheOneItDependsOnWhateverTheOrderOfTheFiles() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("alpha", module("alpha", 5, "dependencies = { \"test:mob/zeta\" },"));
        sources.put("zeta", module("zeta", 3, ""));
        try (MindOverlayCatalog catalog = MindOverlayCatalog.of("test", sources, warnings::add)) {
            assertEquals(List.of("alpha", "zeta"), catalog.behaviors());
            assertTrue(warnings.isEmpty(), warnings.toString());
            // The program of alpha alone runs zeta too.
            assertEquals(2, catalog.program(List.of("alpha")).modules().size());
        }
    }

    @Test
    void behaviorsComposeIntoOneProgramPerCombination() {
        try (MindOverlayCatalog catalog = MindOverlayCatalog.of("test",
                Map.of("coward", module("coward", 5, ""), "guard", module("guard", 3, "")), warnings::add)) {
            MindOverlayCatalog.Program both = catalog.program(List.of("guard", "coward"));
            assertSame(both, catalog.program(List.of("guard", "coward")));
            MindProgram program = both.definition().instantiate(LuaMindContextExtension.NONE);
            assertEquals(List.of("test:mob/guard", "test:mob/coward"),
                    program.behaviors().stream().map(behavior -> behavior.identifier()).toList());
            assertNotEquals(both.definition().programIdentifier(),
                    catalog.program(List.of("guard")).definition().programIdentifier());
            assertEquals("test:mob/", both.idPrefix());
        }
    }

    @Test
    void anUnknownBehaviorIsRefusedWithTheOnesThatLoaded() {
        try (MindOverlayCatalog catalog = MindOverlayCatalog.of("test",
                Map.of("guard", module("guard", 3, "")), warnings::add)) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> catalog.program(List.of("gaurd")));
            assertTrue(refused.getMessage().contains("'gaurd'") && refused.getMessage().contains("guard"),
                    refused.getMessage());
        }
    }

    @Test
    void behaviorsThatDeclareTheSameThingCannotRunTogether() {
        String clash = """
                return ai.module {
                  id = "test:mob/twin", revision = 1,
                  behaviors = { ai.behavior { id = "test:mob/guard", tick = function(c) end } }
                }
                """;
        try (MindOverlayCatalog catalog = MindOverlayCatalog.of("test",
                Map.of("guard", module("guard", 3, ""), "twin", clash), warnings::add)) {
            assertThrows(IllegalArgumentException.class, () -> catalog.program(List.of("guard", "twin")));
            // Each still runs alone.
            catalog.program(List.of("twin"));
        }
    }

    @Test
    void theFolderIsReadWithItsSubfoldersAndOnlyItsLuaFiles(@TempDir Path folder) throws IOException {
        Files.writeString(folder.resolve("guard.lua"), module("guard", 3, ""));
        Files.createDirectories(folder.resolve("pack"));
        Files.writeString(folder.resolve("pack").resolve("coward.lua"), module("coward", 5, ""));
        Files.writeString(folder.resolve("notes.txt"), "not a behavior");
        try (MindOverlayCatalog catalog = MindOverlayCatalog.read("test", folder, warnings::add)) {
            assertEquals(List.of("coward", "guard"), catalog.behaviors());
            assertTrue(warnings.isEmpty(), warnings.toString());
        }
    }

    @Test
    void aFolderThatDoesNotExistHasNoBehaviors(@TempDir Path folder) {
        try (MindOverlayCatalog catalog = MindOverlayCatalog.read("test", folder.resolve("missing"), warnings::add)) {
            assertEquals(List.of(), catalog.behaviors());
            assertTrue(warnings.isEmpty(), warnings.toString());
        }
    }
}
