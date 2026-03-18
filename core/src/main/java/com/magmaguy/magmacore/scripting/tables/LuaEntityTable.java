package com.magmaguy.magmacore.scripting.tables;

import org.bukkit.entity.Entity;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

public class LuaEntityTable {

    public static LuaTable build(Entity entity) {
        LuaTable table = new LuaTable();
        if (entity == null) return table;

        table.set("uuid", entity.getUniqueId().toString());
        table.set("entity_type", entity.getType().name().toLowerCase());
        table.set("is_valid", LuaValue.valueOf(entity.isValid()));

        if (entity.getLocation() != null)
            table.set("current_location", LuaTableSupport.locationToTable(entity.getLocation()));
        if (entity.getWorld() != null)
            table.set("world", entity.getWorld().getName());

        table.set("teleport", LuaTableSupport.tableMethod(table, args -> {
            LuaTable loc = args.arg1().checktable();
            entity.teleport(LuaTableSupport.tableToLocation(loc, entity.getWorld()));
            return LuaValue.NIL;
        }));

        return table;
    }
}
