package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.ai.MindActionRequester;
import com.magmaguy.magmacore.ai.MindActuator;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindMemoryView;
import com.magmaguy.magmacore.ai.MindPerception;
import com.magmaguy.magmacore.ai.MindProgram;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class LuaMindContextExtensionTest {
    static MindContext context() {
        return new MindContext(mock(LivingEntity.class), 1L, 1L, mock(MindMemoryView.class),
                mock(MindPerception.class), mock(MindActuator.class), mock(MindActionRequester.class));
    }

    private static LuaMindDefinition program(LuaMindModuleRegistry registry) {
        LuaMindModuleRegistry.OwnerScope owner = registry.openOwner("fixture");
        owner.registerModule("drift.lua", """
                return ai.module { id = "fixture:drift", revision = 1,
                  sensors = { ai.sensor { id = "fixture:look", sense = function(c)
                    if c.report ~= nil then c.report("sense " .. c.host) end end } },
                  behaviors = { ai.behavior { id = "fixture:drift", tick = function(c)
                    if c.report ~= nil then c.report("tick " .. c.host) end end } } }
                """);
        return owner.validateProgram("pet.lua", """
                return ai.program { id = "fixture:pet", revision = 1, modules = { "fixture:drift" } }
                """);
    }

    @Test
    void callbacksSeeTheHostFieldsAndTheirOwnModule() {
        try (LuaMindModuleRegistry registry = new LuaMindModuleRegistry()) {
            List<String> reports = new ArrayList<>();
            MindProgram program = program(registry).instantiate((table, context, contribution) -> {
                table.set("host", contribution);
                table.set("report", new OneArgFunction() {
                    @Override
                    public LuaValue call(LuaValue message) {
                        reports.add(message.tojstring());
                        return NIL;
                    }
                });
            });
            program.sensors().getFirst().sense(context());
            program.behaviors().getFirst().tick(context());
            assertEquals(List.of("sense fixture:drift", "tick fixture:drift"), reports);
        }
    }

    @Test
    void aPlainInstanceHasNoHostFields() {
        try (LuaMindModuleRegistry registry = new LuaMindModuleRegistry()) {
            MindProgram program = program(registry).instantiate();
            assertDoesNotThrow(() -> program.behaviors().getFirst().tick(context()));
        }
    }
}
