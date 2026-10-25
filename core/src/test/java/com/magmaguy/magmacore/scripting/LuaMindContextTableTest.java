package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.ai.MindActionRequester;
import com.magmaguy.magmacore.ai.MindActuator;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindMemoryView;
import com.magmaguy.magmacore.ai.MindPerception;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LuaMindContextTableTest {
    @Test
    void buildsMindSectionsLazilyAndMemoizesEachCallbackSnapshot() {
        LivingEntity entity = mock(LivingEntity.class);
        UUID entityId = UUID.randomUUID();
        when(entity.getUniqueId()).thenReturn(entityId);
        when(entity.getType()).thenReturn(EntityType.ZOMBIE);

        MindMemoryView memory = mock(MindMemoryView.class);
        MindPerception perception = mock(MindPerception.class);
        MindActuator actuator = mock(MindActuator.class);
        MindActionRequester actions = mock(MindActionRequester.class);
        MindContext context = new MindContext(
                entity,
                42L,
                7L,
                memory,
                perception,
                actuator,
                actions);

        LuaTable table = LuaMindContextTable.build(context, Map.of());

        verifyNoInteractions(entity, memory, perception, actuator, actions);

        LuaValue firstEntity = table.get("entity");
        LuaValue secondEntity = table.get("entity");
        assertSame(firstEntity, secondEntity);
        verify(entity, times(1)).getUniqueId();
        verify(entity, times(1)).getType();

        LuaValue firstMemory = table.get("memory");
        LuaValue secondMemory = table.get("memory");
        assertSame(firstMemory, secondMemory);
        verifyNoInteractions(memory, perception, actuator, actions);
    }

    @Test
    void steerFlightReachesTheActuator() {
        MindActuator actuator = mock(MindActuator.class);
        when(actuator.steerFlight(any())).thenReturn(true);
        LuaValue moved = steerFlight(actuator, LuaValue.valueOf(0.1), LuaValue.valueOf(0.2), LuaValue.valueOf(0.3));
        assertTrue(moved.toboolean());
        verify(actuator).steerFlight(new Vector(0.1, 0.2, 0.3));
    }

    @Test
    void steerFlightReturnsFalseWhenTheBodyCannotFly() {
        MindActuator actuator = mock(MindActuator.class);
        when(actuator.steerFlight(any())).thenReturn(false);
        LuaValue moved = steerFlight(actuator, LuaValue.valueOf(0.1), LuaValue.valueOf(0.2), LuaValue.valueOf(0.3));
        assertSame(LuaValue.FALSE, moved);
    }

    @Test
    void steerFlightRejectsAMissingComponentBeforeReachingTheActuator() {
        MindActuator actuator = mock(MindActuator.class);
        assertThrows(LuaError.class, () -> steerFlight(actuator, LuaValue.valueOf(0.1), LuaValue.valueOf(0.2)));
        verifyNoInteractions(actuator);
    }

    private static LuaValue steerFlight(MindActuator actuator, LuaValue... velocity) {
        MindContext context = new MindContext(mock(LivingEntity.class), 1L, 1L, mock(MindMemoryView.class),
                mock(MindPerception.class), actuator, mock(MindActionRequester.class));
        LuaTable table = LuaMindContextTable.build(context, Map.of());
        return table.get("actuator").invokemethod("steer_flight", LuaValue.varargsOf(velocity)).arg1();
    }
}
