package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.ai.MindActionRequester;
import com.magmaguy.magmacore.ai.MindActuator;
import com.magmaguy.magmacore.ai.MindBehavior;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindMemoryView;
import com.magmaguy.magmacore.ai.MindPerception;
import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.ai.MindSensor;
import com.magmaguy.magmacore.ai.MindStopReason;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LuaMindContextExtensionTest {
    private static final String REPORT = "c.report(kind .. \" \" .. c.host)";
    private static final String NO_HOST_FIELDS = "if c.host ~= nil or c.report ~= nil then error(\"leaked\") end";

    static MindContext context() {
        return new MindContext(mock(LivingEntity.class), 1L, 1L, mock(MindMemoryView.class),
                mock(MindPerception.class), mock(MindActuator.class), mock(MindActionRequester.class));
    }

    /** One sensor and one behavior whose every callback runs {@code report(c, kind)}. */
    private static String callbacks(String behavior) {
        return """
                sensors = { ai.sensor { id = "%1$s_sensor", sense = function(c) report(c, "sense") end } },
                behaviors = { ai.behavior { id = "%1$s",
                  can_start = function(c) report(c, "can_start") return true end,
                  can_continue = function(c) report(c, "can_continue") return true end,
                  start = function(c) report(c, "start") end,
                  tick = function(c) report(c, "tick") end,
                  stop = function(c, reason) report(c, "stop") end } }
                """.formatted(behavior);
    }

    private static String module(String identifier, String behavior, String report) {
        return """
                local function report(c, kind) %s end
                return ai.module { id = "%s", revision = 1,
                %s}
                """.formatted(report, identifier, callbacks(behavior));
    }

    private static String program(String report, String... modules) {
        String moduleList = Arrays.stream(modules).map(module -> "\"" + module + "\"")
                .collect(Collectors.joining(", "));
        return """
                local function report(c, kind) %s end
                return ai.program { id = "fixture:pet", revision = 1, modules = { %s },
                %s}
                """.formatted(report, moduleList, callbacks("fixture:rest"));
    }

    /** Behavior ids differ from their contribution ids, so a report can only name the right contribution. */
    private static LuaMindDefinition pet(LuaMindModuleRegistry registry, String report) {
        LuaMindModuleRegistry.OwnerScope owner = registry.openOwner("fixture");
        owner.registerModule("drift.lua", module("fixture:drift", "fixture:wander", report));
        owner.registerModule("other.lua", module("fixture:other", "fixture:hop", report));
        return owner.validateProgram("pet.lua", program(report, "fixture:drift", "fixture:other"));
    }

    private static LuaMindContextExtension recording(List<String> reports) {
        return (table, context, contribution) -> {
            table.set("host", contribution);
            table.set("report", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue message) {
                    reports.add(message.tojstring());
                    return NIL;
                }
            });
        };
    }

    private static MindBehavior behavior(MindProgram program, String identifier) {
        return program.behaviors().stream().filter(behavior -> behavior.identifier().equals(identifier))
                .findFirst().orElseThrow();
    }

    private static MindSensor sensor(MindProgram program, String identifier) {
        return program.sensors().stream().filter(sensor -> sensor.identifier().equals(identifier))
                .findFirst().orElseThrow();
    }

    private static void runEveryCallback(MindSensor sensor, MindBehavior behavior) {
        sensor.sense(context());
        assertTrue(behavior.canStart(context()));
        assertTrue(behavior.canContinue(context()));
        behavior.start(context());
        behavior.tick(context());
        behavior.stop(context(), MindStopReason.COMPLETED);
    }

    @Test
    void everyCallbackSeesTheHostFields() {
        try (LuaMindModuleRegistry registry = new LuaMindModuleRegistry()) {
            List<String> reports = new ArrayList<>();
            MindProgram program = pet(registry, REPORT).instantiate(recording(reports));
            runEveryCallback(sensor(program, "fixture:wander_sensor"), behavior(program, "fixture:wander"));
            assertEquals(List.of("sense fixture:drift", "can_start fixture:drift", "can_continue fixture:drift",
                    "start fixture:drift", "tick fixture:drift", "stop fixture:drift"), reports);
        }
    }

    @Test
    void eachCallbackSeesTheContributionThatDeclaredIt() {
        try (LuaMindModuleRegistry registry = new LuaMindModuleRegistry()) {
            List<String> reports = new ArrayList<>();
            MindProgram program = pet(registry, REPORT).instantiate(recording(reports));
            for (String identifier : List.of("fixture:wander", "fixture:hop", "fixture:rest")) {
                behavior(program, identifier).tick(context());
                sensor(program, identifier + "_sensor").sense(context());
            }
            assertEquals(List.of("tick fixture:drift", "sense fixture:drift", "tick fixture:other",
                    "sense fixture:other", "tick fixture:pet", "sense fixture:pet"), reports);
        }
    }

    @Test
    void aPlainInstanceHasNoHostFields() {
        try (LuaMindModuleRegistry registry = new LuaMindModuleRegistry()) {
            LuaMindDefinition pet = pet(registry, NO_HOST_FIELDS);
            MindProgram plain = pet.instantiate();
            for (String identifier : List.of("fixture:wander", "fixture:hop", "fixture:rest")) {
                assertDoesNotThrow(() -> runEveryCallback(
                        sensor(plain, identifier + "_sensor"), behavior(plain, identifier)));
            }
            for (String field : List.of("host", "report")) {
                MindProgram leaking = pet.instantiate((table, context, contribution) -> table.set(field, "x"));
                LuaError leak = assertThrows(LuaError.class,
                        () -> behavior(leaking, "fixture:wander").tick(context()));
                assertTrue(leak.getMessage().contains("leaked"), leak.getMessage());
            }
        }
    }

    @Test
    void aThrowingExtensionFailsOnlyItsOwnCallback() {
        try (LuaMindModuleRegistry registry = new LuaMindModuleRegistry()) {
            List<String> reports = new ArrayList<>();
            LuaMindContextExtension record = recording(reports);
            IllegalStateException failure = new IllegalStateException("host failed");
            boolean[] failing = {true};
            MindProgram program = pet(registry, REPORT).instantiate((table, context, contribution) -> {
                if (failing[0]) throw failure;
                record.extend(table, context, contribution);
            });
            MindBehavior wander = behavior(program, "fixture:wander");
            assertSame(failure, assertThrows(IllegalStateException.class, () -> wander.tick(context())));

            // A budget left behind by the failure would absorb this call, whose own instruction limit must trip.
            LuaValue spin = LuaEnvironmentFactory.createGlobals().load("for _ = 1, 10000 do end");
            assertThrows(LuaExecutionBudget.RunawayLimitExceeded.class,
                    () -> LuaExecutionBudget.run(() -> spin.call(), 50_000_000L, 1_000L));

            failing[0] = false;
            wander.tick(context());
            assertEquals(List.of("tick fixture:drift"), reports);
        }
    }

    @Test
    void aModuleCannotShareItsProgramId() {
        try (LuaMindModuleRegistry registry = new LuaMindModuleRegistry()) {
            LuaMindModuleRegistry.OwnerScope owner = registry.openOwner("fixture");
            owner.registerModule("pet-module.lua", module("fixture:pet", "fixture:wander", REPORT));
            IllegalArgumentException clash = assertThrows(IllegalArgumentException.class,
                    () -> owner.validateProgram("pet.lua", program(REPORT, "fixture:pet")));
            assertTrue(clash.getMessage().contains("same id as its program"), clash.getMessage());
        }
    }
}
