package com.magmaguy.magmacore.location;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * Converts Lua location tables (produced by {@code em.create_location(...)} or
 * a plugin's context table) into Bukkit {@link Location}s.
 */
public final class LuaLocations {

    private LuaLocations() {
    }

    /**
     * Convert a Lua table with {@code x, y, z, world} fields into a Bukkit Location.
     * Returns {@code null} if the value is not a table, the {@code world} field is
     * absent, or the named world is not currently loaded.
     */
    public static Location tableToLocation(LuaValue value) {
        if (value == null || !value.istable()) return null;
        LuaTable table = value.checktable();
        LuaValue worldValue = table.get("world");
        World world = resolveWorld(worldValue);
        if (world == null) return null;
        double x = table.get("x").optdouble(0);
        double y = table.get("y").optdouble(0);
        double z = table.get("z").optdouble(0);
        float yaw = (float) table.get("yaw").optdouble(0);
        float pitch = (float) table.get("pitch").optdouble(0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    private static World resolveWorld(LuaValue worldValue) {
        if (worldValue == null || worldValue.isnil()) return null;
        if (worldValue.isstring()) {
            return Bukkit.getWorld(worldValue.tojstring());
        }
        if (worldValue.istable()) {
            LuaValue nameValue = worldValue.get("name");
            if (nameValue.isstring()) {
                return Bukkit.getWorld(nameValue.tojstring());
            }
        }
        return null;
    }
}
