package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.ai.MindRunawayException;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaError;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Mind script that never returns must fail inside the VM instead of hanging the server thread. */
class LuaMindRunawayTest {
    private static final Duration HANG = Duration.ofSeconds(5);
    private static final String NEVER_RETURNS = "function() while true do end end";

    @Test
    void aCallbackThatNeverReturnsIsInterrupted() {
        MindProgram program = LuaMindDefinition.validate("spin.lua", """
                return ai.program { id = "fixture:spin", revision = 1,
                  runaway = { max_instructions = 100000 },
                  behaviors = { ai.behavior { id = "fixture:spin", tick = function(c) while true do end end } } }
                """).instantiate();

        MindRunawayException failure = assertTimeoutPreemptively(HANG, () -> assertThrows(MindRunawayException.class,
                () -> program.behaviors().getFirst().tick(LuaMindContextExtensionTest.context())));
        assertTrue(failure.getMessage().contains("budget exceeded"), failure.getMessage());
    }

    @Test
    void declaredLimitsAboveTheEngineCeilingAreClamped() {
        MindProgram program = LuaMindDefinition.validate("greedy.lua", """
                return ai.program { id = "fixture:greedy", revision = 1,
                  runaway = { max_instructions = 1e12, cpu_micros = 3.6e9 },
                  behaviors = { ai.behavior { id = "fixture:greedy", tick = function(c) while true do end end } } }
                """).instantiate();

        assertTimeoutPreemptively(HANG, () -> assertThrows(MindRunawayException.class,
                () -> program.behaviors().getFirst().tick(LuaMindContextExtensionTest.context())));
        assertEquals(LuaExecutionBudget.MAX_INSTRUCTIONS,
                program.executionPolicy().runawayPolicy().maxInstructionsPerCallback());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(LuaExecutionBudget.MAX_CPU_MILLIS),
                program.executionPolicy().runawayPolicy().maxCpuNanosPerCallback());

        MindProgram legacy = LuaMindDefinition.validate("legacy.lua", """
                return ai.program { id = "fixture:legacy", revision = 1, budget = { max_instructions = 1e12 } }
                """).instantiate();
        assertEquals(LuaExecutionBudget.MAX_INSTRUCTIONS,
                legacy.executionPolicy().runawayPolicy().maxInstructionsPerCallback());
    }

    @Test
    void mindScriptsCannotStartACoroutine() {
        // LuaJ runs a coroutine on its own Java thread, where the execution budget cannot interrupt it.
        MindProgram program = LuaMindDefinition.validate("coroutine.lua", """
                return ai.program { id = "fixture:coroutine", revision = 1,
                  behaviors = { ai.behavior { id = "fixture:coroutine", tick = function(c)
                    coroutine.wrap(function() while true do end end)()
                  end } } }
                """).instantiate();

        LuaError error = assertTimeoutPreemptively(HANG, () -> assertThrows(LuaError.class,
                () -> program.behaviors().getFirst().tick(LuaMindContextExtensionTest.context())));
        assertTrue(error.getMessage().contains("nil value"), error.getMessage());
        assertFalse(LuaEnvironmentFactory.createGlobals().get("coroutine").isnil(),
                "prop scripts and powers keep coroutines");
    }

    @Test
    void aModuleWhoseTopLevelNeverReturnsIsRejected() {
        String source = "while true do end";
        // Not try-with-resources: a registration that never returns keeps the registry's lock, so close() would hang.
        LuaMindModuleRegistry registry = new LuaMindModuleRegistry();
        LuaMindModuleRegistry.OwnerScope owner = registry.openOwner("fixture");
        assertTimeoutPreemptively(HANG, () -> assertThrows(LuaExecutionBudget.RunawayLimitExceeded.class,
                () -> owner.registerModule("spin.lua", source)));
        registry.close();
        assertTimeoutPreemptively(HANG, () -> assertThrows(LuaExecutionBudget.RunawayLimitExceeded.class,
                () -> LuaMindModuleRegistry.inspectModule("spin.lua", source)));
    }

    @Test
    void aModuleTableWhoseIndexNeverReturnsIsRejected() {
        String source = "return ai.module(setmetatable({ id = \"fixture:x\", revision = 1 }, { __index = "
                + NEVER_RETURNS + " }))";
        assertTimeoutPreemptively(HANG, () -> assertThrows(LuaExecutionBudget.RunawayLimitExceeded.class,
                () -> LuaMindModuleRegistry.inspectModule("spin.lua", source)));
    }

    @Test
    void aProgramTableWhoseIndexNeverReturnsIsRejected() {
        String source = "return ai.program(setmetatable({ id = \"fixture:x\", revision = 1 }, { __index = "
                + NEVER_RETURNS + " }))";
        assertTimeoutPreemptively(HANG, () -> assertThrows(LuaExecutionBudget.RunawayLimitExceeded.class,
                () -> LuaMindDefinition.validate("spin.lua", source)));
    }

    @Test
    void scriptsSeeTheMindSandboxAndUnchangedErrorMessages() {
        MindProgram program = LuaMindDefinition.validate("sandbox.lua", """
                local function check()
                  assert(_G == _ENV, "_G is the chunk environment")
                  assert(rawget(_G, "ai") == nil and ai.program ~= nil and ai.controls.move == "move", "ai")
                  assert(em.create_vector ~= nil and em.zone ~= nil and em.location ~= nil, "em")
                  for _, name in ipairs({ "require", "package", "io", "os", "load", "loadfile", "dofile",
                      "luajava", "module", "debug", "coroutine" }) do
                    assert(_G[name] == nil, name .. " is exposed")
                  end
                  assert(math.floor ~= nil and string.format ~= nil and table.insert ~= nil, "standard libraries")
                end
                check()
                chunk_global = 1
                assert(rawget(_G, "chunk_global") == 1, "chunk globals stay in the chunk environment")
                return ai.program { id = "fixture:sandbox", revision = 1,
                  behaviors = { ai.behavior { id = "fixture:check", tick = function(c)
                    check()
                    error("boom")
                  end } } }
                """).instantiate();

        LuaError error = assertThrows(LuaError.class,
                () -> program.behaviors().getFirst().tick(LuaMindContextExtensionTest.context()));
        assertEquals("sandbox.lua:17 boom", error.getMessage(), "no stack traceback is appended");
    }
}
