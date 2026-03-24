package com.magmaguy.magmacore.scripting.tables;

import com.magmaguy.magmacore.MagmaCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.Collection;
import java.util.Locale;

/**
 * Builds the Lua table exposed as {@code context.world} to Lua scripts.
 * Contains generic world operations using pure Bukkit API.
 */
public final class LuaWorldTable {

    private LuaWorldTable() {}

    public static LuaTable build(World world) {
        LuaTable table = new LuaTable();
        if (world == null) return table;

        table.set("name", world.getName());

        // get_block_at(x, y, z) -> block material name
        table.set("get_block_at", method(table, args -> {
            int x = args.checkint(1);
            int y = args.checkint(2);
            int z = args.checkint(3);
            return LuaValue.valueOf(world.getBlockAt(x, y, z).getType().name().toLowerCase(Locale.ROOT));
        }));

        // spawn_particle(particle, x, y, z, count, dx, dy, dz, speed)
        table.set("spawn_particle", method(table, args -> {
            String particleName = args.checkjstring(1).toUpperCase(Locale.ROOT);
            Particle particle;
            try {
                particle = Particle.valueOf(particleName);
            } catch (IllegalArgumentException e) {
                return LuaValue.NIL;
            }
            double x = args.checkdouble(2);
            double y = args.checkdouble(3);
            double z = args.checkdouble(4);
            int count = args.optint(5, 1);
            double dx = args.optdouble(6, 0);
            double dy = args.optdouble(7, 0);
            double dz = args.optdouble(8, 0);
            double speed = args.optdouble(9, 0);
            world.spawnParticle(particle, x, y, z, count, dx, dy, dz, speed);
            return LuaValue.NIL;
        }));

        // get_time() -> world time in ticks
        table.set("get_time", method(table, args ->
                LuaValue.valueOf(world.getTime())));

        // set_time(ticks)
        table.set("set_time", method(table, args -> {
            world.setTime(args.checklong(1));
            return LuaValue.NIL;
        }));

        // play_sound(sound, x, y, z, volume, pitch)
        table.set("play_sound", method(table, args -> {
            String soundName = args.checkjstring(1).toUpperCase(Locale.ROOT);
            Sound sound;
            try {
                sound = Sound.valueOf(soundName);
            } catch (IllegalArgumentException e) {
                return LuaValue.NIL;
            }
            double x = args.checkdouble(2);
            double y = args.checkdouble(3);
            double z = args.checkdouble(4);
            float volume = (float) args.optdouble(5, 1.0);
            float pitch = (float) args.optdouble(6, 1.0);
            world.playSound(new Location(world, x, y, z), sound, volume, pitch);
            return LuaValue.NIL;
        }));

        // strike_lightning(x, y, z)
        table.set("strike_lightning", method(table, args -> {
            double x = args.checkdouble(1);
            double y = args.checkdouble(2);
            double z = args.checkdouble(3);
            world.strikeLightning(new Location(world, x, y, z));
            return LuaValue.NIL;
        }));

        // get_nearby_entities(x, y, z, radius) -> array of entity tables
        table.set("get_nearby_entities", method(table, args -> {
            double x = args.checkdouble(1);
            double y = args.checkdouble(2);
            double z = args.checkdouble(3);
            double radius = args.checkdouble(4);
            Location center = new Location(world, x, y, z);
            Collection<Entity> nearby = world.getNearbyEntities(center, radius, radius, radius);
            LuaTable result = new LuaTable();
            int index = 1;
            for (Entity entity : nearby) {
                if (entity instanceof LivingEntity livingEntity) {
                    result.set(index++, LuaLivingEntityTable.build(livingEntity));
                } else {
                    result.set(index++, LuaEntityTable.build(entity));
                }
            }
            return result;
        }));

        // spawn_entity(entity_type, x, y, z) -> entity table (vanilla entities only)
        table.set("spawn_entity", method(table, args -> {
            String entityTypeName = args.checkjstring(1).toUpperCase(Locale.ROOT);
            EntityType entityType;
            try {
                entityType = EntityType.valueOf(entityTypeName);
            } catch (IllegalArgumentException e) {
                return LuaValue.NIL;
            }
            double x = args.checkdouble(2);
            double y = args.checkdouble(3);
            double z = args.checkdouble(4);
            Entity spawned = world.spawnEntity(new Location(world, x, y, z), entityType);
            if (spawned instanceof LivingEntity livingEntity) {
                return LuaLivingEntityTable.build(livingEntity);
            }
            return LuaEntityTable.build(spawned);
        }));

        // set_block_at(x, y, z, material) -> true/false
        table.set("set_block_at", method(table, args -> {
            int x = args.checkint(1);
            int y = args.checkint(2);
            int z = args.checkint(3);
            String materialName = args.checkjstring(4);
            Material material = Material.matchMaterial(materialName);
            if (material == null) return LuaValue.FALSE;
            Bukkit.getScheduler().runTask(MagmaCore.getInstance().getRequestingPlugin(), () ->
                    world.getBlockAt(x, y, z).setType(material));
            return LuaValue.TRUE;
        }));

        // get_nearby_players(x, y, z, radius) -> array of player tables
        table.set("get_nearby_players", method(table, args -> {
            double x = args.checkdouble(1);
            double y = args.checkdouble(2);
            double z = args.checkdouble(3);
            double radius = args.checkdouble(4);
            Location center = new Location(world, x, y, z);
            Collection<Entity> nearby = world.getNearbyEntities(center, radius, radius, radius);
            LuaTable result = new LuaTable();
            int index = 1;
            for (Entity entity : nearby) {
                if (entity instanceof Player) {
                    result.set(index++, LuaLivingEntityTable.build((LivingEntity) entity));
                }
            }
            return result;
        }));

        return table;
    }

    // ── Lua method-call boilerplate ────────────────────────────────────

    private static VarArgFunction method(LuaTable owner, LuaTableSupport.LuaCallback callback) {
        return new VarArgFunction() {
            @Override
            public org.luaj.vm2.Varargs invoke(org.luaj.vm2.Varargs args) {
                return callback.invoke(LuaTableSupport.stripMethodSelf(args, owner));
            }
        };
    }
}
