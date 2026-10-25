package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.ai.MindProgram;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LuaMindCompiledSourceTest {
    private static LuaMindContextExtension reporter(List<Double> into) {
        return (table, context, contribution) -> table.set("report", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                into.add(value.todouble());
                return NIL;
            }
        });
    }

    @Test
    void instancesReuseTheCompiledSourceButNotItsState() {
        try (LuaMindModuleRegistry registry = new LuaMindModuleRegistry()) {
            LuaMindModuleRegistry.OwnerScope owner = registry.openOwner("fixture");
            owner.registerModule("counter.lua", """
                    local count = 0
                    return ai.module { id = "fixture:counter", revision = 1,
                      behaviors = { ai.behavior { id = "fixture:count",
                        tick = function(c) count = count + 1; c.report(count) end } } }
                    """);
            LuaMindDefinition definition = owner.validateProgram("pet.lua", """
                    return ai.program { id = "fixture:pet", revision = 1, modules = { "fixture:counter" } }
                    """);
            long compiled = LuaMindSourceReader.compilations();
            List<Double> first = new ArrayList<>(), second = new ArrayList<>();
            MindProgram a = definition.instantiate(reporter(first));
            MindProgram b = definition.instantiate(reporter(second));
            assertEquals(compiled, LuaMindSourceReader.compilations(), "instantiating compiles nothing");
            a.behaviors().getFirst().tick(LuaMindContextExtensionTest.context());
            a.behaviors().getFirst().tick(LuaMindContextExtensionTest.context());
            b.behaviors().getFirst().tick(LuaMindContextExtensionTest.context());
            assertEquals(List.of(1D, 2D), first);
            assertEquals(List.of(1D), second, "each instance keeps its own module locals");
        }
    }
}
