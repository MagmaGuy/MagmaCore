package com.magmaguy.magmacore.scripting;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** LuaJ gives every string one metatable for the whole JVM, so no script may reach it. */
class LuaStringMetatableTest {
    private static LuaMindContextExtension reporter(List<String> into) {
        return (table, context, contribution) -> table.set("report", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                into.add(value.tojstring());
                return NIL;
            }
        });
    }

    @Test
    void oneMindInstanceCannotChangeStringMethodsForAnother() {
        LuaMindDefinition vandal = LuaMindDefinition.validate("vandal.lua", """
                return ai.program { id = "fixture:vandal", revision = 1,
                  behaviors = { ai.behavior { id = "fixture:vandal", tick = function(c)
                    c.report(tostring(getmetatable("")))
                    string.upper = function() return "changed" end
                    pcall(function() getmetatable("").__index.upper = string.upper end)
                  end } } }
                """);
        LuaMindDefinition victim = LuaMindDefinition.validate("victim.lua", """
                return ai.program { id = "fixture:victim", revision = 1,
                  behaviors = { ai.behavior { id = "fixture:victim", tick = function(c) c.report(("a"):upper()) end } } }
                """);
        List<String> reports = new ArrayList<>();

        // LuaJ makes the first globals' string library the table every string indexes. Forget the current one so
        // the vandal's live globals come first, as they would if a script instance were the first Lua on a server.
        LuaString.s_metatable = null;
        vandal.instantiate(reporter(reports)).behaviors().getFirst().tick(LuaMindContextExtensionTest.context());
        victim.instantiate(reporter(reports)).behaviors().getFirst().tick(LuaMindContextExtensionTest.context());

        assertEquals(List.of("false", "A"), reports);
    }
}
