package com.magmaguy.magmacore.scripting;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaMindModuleCompositionContractTest {
    @Test
    void composesSharedTargetPursueCombatAndFlightModules() {
        LuaMindModuleRegistry registry = new LuaMindModuleRegistry();
        LuaMindModuleRegistry.OwnerScope owner = registry.openOwner("fixture");
        registerMovementFixture(owner);

        LuaMindDefinition definition = owner.validateProgram("hybrid.lua", """
                if require ~= nil or package ~= nil or io ~= nil or os ~= nil
                        or loadfile ~= nil or dofile ~= nil or luajava ~= nil then
                    error("Mind sandbox exposed an unrestricted loader")
                end
                return ai.program {
                  id = "fixture:hybrid",
                  revision = 3,
                  modules = {
                    "fixture:melee",
                    "fixture:ranged",
                    "fixture:flying"
                  },
                  budget = {
                    max_callbacks = 24,
                    max_instructions = 10000,
                    max_action_requests = 4
                  }
                }
                """);

        assertEquals(List.of(
                        "fixture:target",
                        "fixture:pursue",
                        "fixture:melee",
                        "fixture:ranged",
                        "fixture:flying"),
                definition.resolvedModules().stream()
                        .map(LuaMindModuleDescriptor::identifier)
                        .toList());
        assertEquals(List.of(1L, 1L, 1L, 1L, 1L),
                definition.resolvedModules().stream()
                        .map(LuaMindModuleDescriptor::revision)
                        .toList());
        assertEquals(64, definition.programFingerprint().length());

        var first = definition.instantiate();
        var second = definition.instantiate();
        assertEquals(
                10_000L,
                first.executionPolicy().runawayPolicy().maxInstructionsPerCallback());
        assertEquals(1, first.sensors().size());
        assertEquals(4, first.behaviors().size());
        assertNotSame(first.sensors().getFirst(), second.sensors().getFirst());
        assertNotSame(first.behaviors().getFirst(), second.behaviors().getFirst());
        assertDoesNotThrow(first::claimForBinding);
        assertDoesNotThrow(second::claimForBinding);

        owner.close();
        assertDoesNotThrow(definition::instantiate);
        registry.close();
    }

    @Test
    void rejectsMissingDependenciesAndCyclesWithoutReplacingCurrentRevision() {
        LuaMindModuleRegistry registry = new LuaMindModuleRegistry();
        LuaMindModuleRegistry.OwnerScope owner = registry.openOwner("fixture");

        assertThrows(IllegalArgumentException.class, () -> owner.registerModule(
                "missing.lua",
                module("fixture:missing", 1, List.of("fixture:not_registered"), "", "", "")));
        assertTrue(owner.modules().isEmpty());

        assertThrows(IllegalArgumentException.class, () -> owner.registerModule(
                "fixture:expected",
                1,
                "wrong-identity.lua",
                module("fixture:wrong", 1, List.of(), "", "", "")));
        assertTrue(owner.modules().isEmpty());

        owner.registerModule(
                "a.lua",
                module("fixture:a", 1, List.of(), "", "", ""));
        owner.registerModule(
                "b.lua",
                module("fixture:b", 1, List.of("fixture:a"), "", "", ""));

        IllegalArgumentException cycle = assertThrows(
                IllegalArgumentException.class,
                () -> owner.registerModule(
                        "a-v2.lua",
                        module("fixture:a", 2, List.of("fixture:b"), "", "", "")));
        assertTrue(cycle.getMessage().contains("fixture:a -> fixture:b -> fixture:a"));
        assertEquals(1L, owner.module("fixture:a").orElseThrow().revision());
    }

    @Test
    void rejectsMemorySensorAndBehaviorCollisionsAtComposition() {
        LuaMindModuleRegistry registry = new LuaMindModuleRegistry();
        LuaMindModuleRegistry.OwnerScope owner = registry.openOwner("fixture");

        owner.registerModule("memory-a.lua", module(
                "fixture:memory_a", 1, List.of(),
                "shared = \"uuid\"", "", ""));
        owner.registerModule("memory-b.lua", module(
                "fixture:memory_b", 1, List.of(),
                "shared = \"string\"", "", ""));
        assertCollision(owner, "fixture:memory_a", "fixture:memory_b", "memory");

        owner.registerModule("sensor-a.lua", module(
                "fixture:sensor_a", 1, List.of(), "",
                "ai.sensor { id = \"fixture:shared_sensor\", sense = function(context) end }", ""));
        owner.registerModule("sensor-b.lua", module(
                "fixture:sensor_b", 1, List.of(), "",
                "ai.sensor { id = \"fixture:shared_sensor\", sense = function(context) end }", ""));
        assertCollision(owner, "fixture:sensor_a", "fixture:sensor_b", "sensor");

        owner.registerModule("behavior-a.lua", module(
                "fixture:behavior_a", 1, List.of(), "", "",
                "ai.behavior { id = \"fixture:shared_behavior\", tick = function(context) end }"));
        owner.registerModule("behavior-b.lua", module(
                "fixture:behavior_b", 1, List.of(), "", "",
                "ai.behavior { id = \"fixture:shared_behavior\", tick = function(context) end }"));
        assertCollision(owner, "fixture:behavior_a", "fixture:behavior_b", "behavior");
    }

    @Test
    void replacementAdvancesFingerprintWhileExistingDefinitionKeepsItsSnapshot() {
        LuaMindModuleRegistry registry = new LuaMindModuleRegistry();
        LuaMindModuleRegistry.OwnerScope owner = registry.openOwner("fixture");
        String firstSource = module(
                "fixture:target", 1, List.of(), "target = \"uuid\"", "", "");
        assertEquals(
                owner.registerModule("target.lua", firstSource),
                owner.registerModule("renamed-target.lua", firstSource));

        String program = """
                return ai.program {
                  id = "fixture:snapshot",
                  revision = 1,
                  modules = { "fixture:target" }
                }
                """;
        LuaMindDefinition before = owner.validateProgram("snapshot.lua", program);

        assertThrows(IllegalArgumentException.class, () -> owner.registerModule(
                "conflict.lua",
                module("fixture:target", 1, List.of(), "other = \"uuid\"", "", "")));
        owner.registerModule(
                "target-v2.lua",
                module("fixture:target", 2, List.of(), "target = \"uuid\"", "", ""));
        LuaMindDefinition after = owner.validateProgram("snapshot.lua", program);

        assertEquals(1L, before.resolvedModules().getFirst().revision());
        assertEquals(2L, after.resolvedModules().getFirst().revision());
        assertNotEquals(before.programFingerprint(), after.programFingerprint());

        owner.close();
        assertDoesNotThrow(before::instantiate);
        assertDoesNotThrow(after::instantiate);
    }

    @Test
    void moduleGlobalsAreIsolatedInsideEveryFreshProgramRuntime() {
        LuaMindModuleRegistry registry = new LuaMindModuleRegistry();
        LuaMindModuleRegistry.OwnerScope owner = registry.openOwner("fixture");
        owner.registerModule("first.lua", """
                private_counter = 0
                return ai.module {
                  id = "fixture:first",
                  revision = 1,
                  behaviors = {
                    ai.behavior {
                      id = "fixture:first_behavior",
                      tick = function(context) private_counter = private_counter + 1 end
                    }
                  }
                }
                """);
        owner.registerModule("second.lua", """
                if private_counter ~= nil then error("module global leaked") end
                private_counter = 100
                return ai.module {
                  id = "fixture:second",
                  revision = 1,
                  behaviors = {
                    ai.behavior {
                      id = "fixture:second_behavior",
                      tick = function(context) private_counter = private_counter + 1 end
                    }
                  }
                }
                """);
        LuaMindDefinition definition = owner.validateProgram("isolated.lua", """
                return ai.program {
                  id = "fixture:isolated",
                  revision = 1,
                  modules = { "fixture:first", "fixture:second" }
                }
                """);

        var first = definition.instantiate();
        var second = definition.instantiate();
        assertNotSame(first.behaviors().getFirst(), second.behaviors().getFirst());
        assertNotSame(first.behaviors().getLast(), second.behaviors().getLast());
    }

    @Test
    void ownerCleanupRemovesCatalogAndReleasesNamespace() {
        LuaMindModuleRegistry registry = new LuaMindModuleRegistry();
        LuaMindModuleRegistry.OwnerScope first = registry.openOwner("fixture");
        first.registerModule(
                "module.lua",
                module("fixture:module", 1, List.of(), "", "", ""));
        assertThrows(IllegalStateException.class, () -> registry.openOwner("fixture"));

        first.close();
        assertThrows(IllegalStateException.class, first::modules);

        LuaMindModuleRegistry.OwnerScope replacement = registry.openOwner("fixture");
        assertTrue(replacement.modules().isEmpty());
        registry.close();
        assertThrows(IllegalStateException.class, replacement::modules);
        assertThrows(IllegalStateException.class, () -> registry.openOwner("other"));
    }

    private static void registerMovementFixture(LuaMindModuleRegistry.OwnerScope owner) {
        owner.registerModule("target.lua", module(
                "fixture:target", 1, List.of(),
                "target = { type = \"uuid\", persistent = false }",
                """
                ai.sensor {
                  id = "fixture:nearest_target",
                  interval = 10,
                  sense = function(context)
                    local target = context.perception:nearest_player(128)
                    if target ~= nil then context.memory:set("target", target.uuid, 30) end
                  end
                }
                """, ""));
        owner.registerModule("pursue.lua", module(
                "fixture:pursue", 1, List.of("fixture:target"), "", "", """
                ai.behavior {
                  id = "fixture:pursue",
                  priority = 10,
                  controls = { ai.controls.move, ai.controls.look, ai.controls.target },
                  can_start = function(context) return context.memory:contains("target") end,
                  tick = function(context)
                    local target = context.memory:get("target")
                    if target ~= nil then context.actuator:set_target(target) end
                  end
                }
                """));
        owner.registerModule("melee.lua", module(
                "fixture:melee", 1, List.of("fixture:pursue"),
                "next_attack_tick = \"integer\"", "", """
                ai.behavior {
                  id = "fixture:melee",
                  priority = 20,
                  controls = { ai.controls.attack },
                  tick = function(context)
                    local target = context.perception:current_target()
                    if target ~= nil then context.actuator:attack(target) end
                  end
                }
                """));
        owner.registerModule("ranged.lua", module(
                "fixture:ranged", 1, List.of("fixture:target"), "", "", """
                ai.behavior {
                  id = "fixture:ranged",
                  priority = 30,
                  controls = { ai.controls.action },
                  tick = function(context)
                    context.actions:request("fixture:fire_projectile", {})
                  end
                }
                """));
        owner.registerModule("flying.lua", module(
                "fixture:flying", 1, List.of("fixture:pursue"), "", "", """
                ai.behavior {
                  id = "fixture:flying",
                  priority = 40,
                  controls = { ai.controls.jump },
                  tick = function(context) context.actuator:jump() end
                }
                """));
    }

    private static void assertCollision(
            LuaMindModuleRegistry.OwnerScope owner,
            String first,
            String second,
            String expectedKind) {
        IllegalArgumentException collision = assertThrows(
                IllegalArgumentException.class,
                () -> owner.validateProgram("collision.lua", """
                        return ai.program {
                          id = "fixture:collision",
                          revision = 1,
                          modules = { "%s", "%s" }
                        }
                        """.formatted(first, second)));
        assertTrue(collision.getMessage().toLowerCase().contains(expectedKind));
    }

    private static String module(
            String identifier,
            long revision,
            List<String> dependencies,
            String memories,
            String sensors,
            String behaviors) {
        return """
                return ai.module {
                  id = "%s",
                  revision = %d,
                  dependencies = { %s },
                  memories = { %s },
                  sensors = { %s },
                  behaviors = { %s }
                }
                """.formatted(
                identifier,
                revision,
                dependencies.stream()
                        .map(value -> "\"" + value + "\"")
                        .reduce((left, right) -> left + ", " + right)
                        .orElse(""),
                memories,
                sensors,
                behaviors);
    }
}
