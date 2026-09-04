package com.magmaguy.magmacore.scripting.tables;

import com.magmaguy.magmacore.visuals.terrain.TerrainImpactHandle;
import com.magmaguy.magmacore.visuals.terrain.TerrainImpactRequest;
import com.magmaguy.magmacore.visuals.terrain.TerrainImpactService;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LuaWorldTerrainImpactTest {

    @BeforeEach
    @AfterEach
    void resetRenderer() {
        TerrainImpactService.shutdown();
    }

    @Test
    void delegatesOptionsAndCrackPresetThroughOpaqueLuaHandles() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("arena");
        when(world.getUID()).thenReturn(UUID.fromString("f7740f62-c0e1-44c3-bf9b-a999a5584fdd"));
        List<TerrainImpactRequest> requests = new ArrayList<>();
        List<MutableHandle> handles = new ArrayList<>();
        TerrainImpactService.install(request -> {
            requests.add(request);
            MutableHandle handle = new MutableHandle(UUID.randomUUID());
            handles.add(handle);
            return handle;
        });
        LuaTable worldTable = LuaWorldTable.build(world);
        LuaTable location = location(4.25, 68.0, -9.5);
        LuaTable options = new LuaTable();
        options.set("radius", 3.25);
        options.set("intensity", 0.4);
        options.set("duration_ticks", 60);
        options.set("view_range", 72.0);
        options.set("seed", 1234L);

        LuaValue configuredHandle = worldTable.get("terrain_impact_at_location").call(location, options);
        LuaValue presetHandle = worldTable.get("terrain_impact").invoke(LuaValue.varargsOf(new LuaValue[] {
                LuaValue.valueOf(8.0), LuaValue.valueOf(70.0), LuaValue.valueOf(2.0)
        })).arg1();

        assertEquals(2, requests.size());
        TerrainImpactRequest configured = requests.get(0);
        assertSame(world, configured.center().getWorld());
        assertEquals(4.25, configured.center().getX());
        assertEquals(3.25, configured.radius());
        assertEquals(0.4, configured.intensity());
        assertEquals(60, configured.durationTicks());
        assertEquals(72.0, configured.viewRange());
        assertEquals(1234L, configured.seed());

        TerrainImpactRequest preset = requests.get(1);
        assertEquals(8.0, preset.center().getX());
        assertEquals(4.5, preset.radius());
        assertEquals(1.0, preset.intensity());
        assertEquals(35, preset.durationTicks());
        assertEquals(48.0, preset.viewRange());

        assertEquals(handles.get(0).id().toString(), configuredHandle.get("id").tojstring());
        assertTrue(configuredHandle.get("is_active").call().toboolean());
        assertTrue(configuredHandle.get("remove").call().toboolean());
        assertFalse(configuredHandle.get("is_active").call().toboolean());
        assertFalse(configuredHandle.get("remove").call().toboolean());
        assertTrue(presetHandle.istable());

        TerrainImpactService.shutdown();
        assertTrue(worldTable.get("terrain_impact_at_location").call(location).isnil());
    }

    private static LuaTable location(double x, double y, double z) {
        LuaTable table = new LuaTable();
        table.set("x", x);
        table.set("y", y);
        table.set("z", z);
        return table;
    }

    private static final class MutableHandle implements TerrainImpactHandle {
        private final UUID id;
        private boolean active = true;

        private MutableHandle(UUID id) {
            this.id = id;
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public void close() {
            active = false;
        }
    }
}
