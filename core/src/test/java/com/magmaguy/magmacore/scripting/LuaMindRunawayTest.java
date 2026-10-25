package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.ai.MindRunawayException;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaError;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void aModuleWhoseTopLevelNeverReturnsIsRejected() {
        String source = "while true do end";
        // Not try-with-resources: a registration that never returns keeps the registry's lock, so close() would hang.
        LuaMindModuleRegistry registry = new LuaMindModuleRegistry();
        LuaMindModuleRegistry.OwnerScope owner = registry.openOwner("fixture");
        assertTimeoutPreemptively(HANG, () -> assertThrows(LuaError.class,
                () -> owner.registerModule("spin.lua", source)));
        registry.close();
        assertTimeoutPreemptively(HANG, () -> assertThrows(LuaError.class,
                () -> LuaMindModuleRegistry.inspectModule("spin.lua", source)));
    }

    @Test
    void aModuleTableWhoseIndexNeverReturnsIsRejected() {
        String source = "return ai.module(setmetatable({ id = \"fixture:x\", revision = 1 }, { __index = "
                + NEVER_RETURNS + " }))";
        assertTimeoutPreemptively(HANG, () -> assertThrows(LuaError.class,
                () -> LuaMindModuleRegistry.inspectModule("spin.lua", source)));
    }

    @Test
    void aProgramTableWhoseIndexNeverReturnsIsRejected() {
        String source = "return ai.program(setmetatable({ id = \"fixture:x\", revision = 1 }, { __index = "
                + NEVER_RETURNS + " }))";
        assertTimeoutPreemptively(HANG, () -> assertThrows(LuaError.class,
                () -> LuaMindDefinition.validate("spin.lua", source)));
    }

    @Test
    void scriptsSeeTheSameSandboxAndErrorMessages() {
        MindProgram program = LuaMindDefinition.validate("sandbox.lua", """
                local function check()
                  assert(_G == _ENV, "_G is the chunk environment")
                  assert(rawget(_G, "ai") == nil and ai.program ~= nil and ai.controls.move == "move", "ai")
                  assert(em.create_vector ~= nil and em.zone ~= nil and em.location ~= nil, "em")
                  for _, name in ipairs({ "require", "package", "io", "os", "load", "loadfile", "dofile",
                      "luajava", "module", "debug" }) do
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
