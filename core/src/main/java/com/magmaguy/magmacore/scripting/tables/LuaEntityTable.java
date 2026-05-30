package com.magmaguy.magmacore.scripting.tables;

import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

public class LuaEntityTable {

    private static final List<BiConsumer<LuaTable, Entity>> enrichers = new CopyOnWriteArrayList<>();

    /**
     * Registers an enricher that adds plugin-specific fields (e.g. is_elite, is_modeled,
     * sub-tables) to every built entity table. Consumers should register on plugin enable
     * using direct class imports — this registry is local to this copy of Magmacore, so
     * plugins that ship their own shaded copy must register with each other's copies
     * directly via their public API classes.
     */
    public static void registerEnricher(BiConsumer<LuaTable, Entity> enricher) {
        if (enricher == null) return;
        enrichers.add(enricher);
    }

    public static LuaTable build(Entity entity) {
        LuaTable table = new LuaTable();
        if (entity == null) return table;

        table.set("uuid", entity.getUniqueId().toString());
        table.set("entity_type", entity.getType().name().toLowerCase());

        LuaTableSupport.lazyField(table, "is_valid", () -> LuaValue.valueOf(entity.isValid()));
        LuaTableSupport.lazyField(table, "is_dead", () -> LuaValue.valueOf(entity.isDead()));
        LuaTableSupport.lazyField(table, "current_location", () -> {
            org.bukkit.Location loc = entity.getLocation();
            return loc != null ? LuaTableSupport.locationToTable(loc) : LuaValue.NIL;
        });
        LuaTableSupport.lazyField(table, "world", () -> {
            org.bukkit.World w = entity.getWorld();
            return w != null ? LuaValue.valueOf(w.getName()) : LuaValue.NIL;
        });

        table.set("teleport", LuaTableSupport.tableMethod(table, args -> {
            LuaTable loc = args.arg1().checktable();
            entity.teleport(LuaTableSupport.tableToLocation(loc, entity.getWorld()));
            return LuaValue.NIL;
        }));

        table.set("remove", LuaTableSupport.tableMethod(table, args -> {
            if (entity.isValid()) entity.remove();
            return LuaValue.NIL;
        }));

        table.set("is_player", LuaValue.valueOf(entity instanceof Player));
        table.set("is_hostile", LuaValue.valueOf(entity instanceof Monster));
        table.set("is_passive", LuaValue.valueOf(entity instanceof Animals));

        table.set("set_silent", LuaTableSupport.tableMethod(table, args -> {
            entity.setSilent(args.checkboolean(1));
            return LuaValue.NIL;
        }));

        table.set("set_invulnerable", LuaTableSupport.tableMethod(table, args -> {
            entity.setInvulnerable(args.checkboolean(1));
            return LuaValue.NIL;
        }));

        table.set("set_gravity", LuaTableSupport.tableMethod(table, args -> {
            entity.setGravity(args.checkboolean(1));
            return LuaValue.NIL;
        }));

        table.set("set_glowing", LuaTableSupport.tableMethod(table, args -> {
            entity.setGlowing(args.checkboolean(1));
            return LuaValue.NIL;
        }));

        for (BiConsumer<LuaTable, Entity> enricher : enrichers) {
            try {
                enricher.accept(table, entity);
            } catch (Throwable ignored) {
                // Enricher failure must not poison the whole table.
            }
        }

        return table;
    }
}
