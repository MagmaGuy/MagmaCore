package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.ai.MindActionRequester;
import com.magmaguy.magmacore.ai.MindActuator;
import com.magmaguy.magmacore.ai.MindBehavior;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindMemoryStore;
import com.magmaguy.magmacore.ai.MindMemoryView;
import com.magmaguy.magmacore.ai.MindPerception;
import com.magmaguy.magmacore.ai.MindProgram;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** c.memory and the context's sections, as a compiled program's callbacks see them. */
class LuaMindMemoryApiTest {
    private final MindMemoryStore[] store = new MindMemoryStore[1];

    /** Runs {@code body} as a behavior's tick on game tick {@code tick}; {@code c.report(value)} collects values. */
    private List<String> tick(String body, long... ticks) {
        List<String> reports = new ArrayList<>();
        MindProgram program = LuaMindDefinition.validate("memory.lua", """
                return ai.program { id = "fixture:memory", revision = 1,
                  memories = { count = 'integer', spot = 'position', name = { type = 'string', persistent = true } },
                  behaviors = { ai.behavior { id = "fixture:memory", tick = function(c) %s end } } }
                """.formatted(body)).instantiate((table, context, contribution) ->
                table.set("report", new OneArgFunction() {
                    @Override
                    public LuaValue call(LuaValue value) {
                        reports.add(value.tojstring());
                        return NIL;
                    }
                }));
        if (store[0] == null) store[0] = new MindMemoryStore(program.schema());
        MindBehavior behavior = program.behaviors().get(0);
        for (long tick : ticks) behavior.tick(context(tick));
        return reports;
    }

    private MindContext context(long tick) {
        return new MindContext(mock(LivingEntity.class), tick, 1L, store[0].view(tick), mock(MindPerception.class),
                mock(MindActuator.class), mock(MindActionRequester.class));
    }

    @Test
    void methodsWorkWithAColonOrADotAndByAliasOrIdentifier() {
        List<String> reports = tick("""
                c.memory:set('count', 3)
                c.report(c.memory:get('count'))
                c.report(c.memory.get('fixture:count'))
                c.report(c.memory.contains('count'))
                c.memory.forget('count')
                c.report(c.memory:contains('fixture:count'))
                c.report(c.memory:get('count'))
                c.memory.set('name', 'jelly')
                c.report(c.memory:get('name'))
                """, 10L);
        assertEquals(List.of("3", "3", "true", "false", "nil", "jelly"), reports);
    }

    @Test
    void aPositionComesBackAsAFreshWorldXYZTable() {
        List<String> reports = tick("""
                c.memory:set('spot', { world = 'world', x = 1.5, y = 64, z = -2 })
                local first, second = c.memory:get('spot'), c.memory:get('spot')
                first.x = 99
                c.report(second.world .. ' ' .. second.x .. ' ' .. second.y .. ' ' .. second.z)
                """, 10L);
        assertEquals(List.of("world 1.5 64 -2"), reports);
    }

    @Test
    void aMemorySetWithATimeToLiveExpires() {
        tick("if c.tick == 10 then c.memory:set('count', 1, 5) end", 10L);
        assertEquals(List.of("true", "true", "false"),
                tick("c.report(c.memory:contains('count'))", 11L, 14L, 15L));
    }

    @Test
    void anUndeclaredMemoryFailsTheCallback() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> tick("c.memory:get('nope')", 10L));
        assertTrue(String.valueOf(error.getMessage()).contains("Undeclared mind memory 'nope'"), error.getMessage());
        assertThrows(LuaError.class, () -> tick("c.memory:get()", 10L));
    }

    @Test
    void aSectionReadOnceIsStoredOnTheContextTableAndKeepsItsSnapshot() {
        MindContext context = new MindContext(mock(LivingEntity.class), 1L, 1L, mock(MindMemoryView.class),
                mock(MindPerception.class), mock(MindActuator.class), mock(MindActionRequester.class));
        LuaTable table = LuaMindContextTable.build(context, Map.of());
        assertTrue(table.rawget("memory").isnil());
        LuaValue memory = table.get("memory");
        assertSame(memory, table.rawget("memory"));
        table.set("memory", LuaValue.NIL);
        assertSame(memory, table.get("memory"));
        assertTrue(table.get("unknown").isnil());
    }
}
