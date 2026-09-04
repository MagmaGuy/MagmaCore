package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.ai.MindActionRequester;
import com.magmaguy.magmacore.ai.MindActuator;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindMemoryView;
import com.magmaguy.magmacore.ai.MindPerception;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
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
}
