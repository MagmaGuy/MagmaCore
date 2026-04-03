package com.magmaguy.magmacore.scripting.tables;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.util.TemporaryBlockManager;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

        // get_highest_block_y(x, z) -> y coordinate of highest non-air block
        table.set("get_highest_block_y", method(table, args -> {
            int x = args.checkint(1);
            int z = args.checkint(2);
            return LuaValue.valueOf(world.getHighestBlockYAt(x, z));
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

        // raycast(from_x, from_y, from_z, dir_x, dir_y, dir_z, max_distance)
        table.set("raycast", method(table, args -> {
            double fx = args.checkdouble(1);
            double fy = args.checkdouble(2);
            double fz = args.checkdouble(3);
            double dx = args.checkdouble(4);
            double dy = args.checkdouble(5);
            double dz = args.checkdouble(6);
            double maxDist = args.optdouble(7, 50);

            Location start = new Location(world, fx, fy, fz);
            Vector direction = new Vector(dx, dy, dz).normalize();

            RayTraceResult result = world.rayTrace(start, direction, maxDist,
                    FluidCollisionMode.NEVER, true, 0.5, null);

            LuaTable resultTable = new LuaTable();
            if (result == null) {
                resultTable.set("hit_entity", LuaValue.NIL);
                resultTable.set("hit_location", LuaValue.NIL);
                resultTable.set("hit_block", LuaValue.NIL);
                return resultTable;
            }

            if (result.getHitEntity() != null) {
                Entity hitEntity = result.getHitEntity();
                if (hitEntity instanceof LivingEntity le) {
                    resultTable.set("hit_entity", LuaLivingEntityTable.build(le));
                } else {
                    resultTable.set("hit_entity", LuaEntityTable.build(hitEntity));
                }
            } else {
                resultTable.set("hit_entity", LuaValue.NIL);
            }

            if (result.getHitPosition() != null) {
                Vector hitPos = result.getHitPosition();
                resultTable.set("hit_location", LuaTableSupport.locationToTable(
                        new Location(world, hitPos.getX(), hitPos.getY(), hitPos.getZ())));
            }

            if (result.getHitBlock() != null) {
                Block hitBlock = result.getHitBlock();
                LuaTable blockTable = new LuaTable();
                blockTable.set("x", hitBlock.getX());
                blockTable.set("y", hitBlock.getY());
                blockTable.set("z", hitBlock.getZ());
                blockTable.set("material", hitBlock.getType().name().toLowerCase(Locale.ROOT));
                resultTable.set("hit_block", blockTable);
            } else {
                resultTable.set("hit_block", LuaValue.NIL);
            }

            return resultTable;
        }));

        // place_temporary_block(x, y, z, material, ticks, require_air?)
        table.set("place_temporary_block", method(table, args -> {
            int x = args.checkint(1);
            int y = args.checkint(2);
            int z = args.checkint(3);
            String materialName = args.checkjstring(4);
            int ticks = args.optint(5, 0);
            boolean requireAir = args.optboolean(6, false);
            Material material = Material.matchMaterial(materialName);
            if (material == null) return LuaValue.FALSE;
            Block block = world.getBlockAt(x, y, z);
            TemporaryBlockManager.addTemporaryBlock(block, ticks, material, requireAir);
            return LuaValue.TRUE;
        }));

        // drop_item(x, y, z, material, amount?) -> dropped item entity table or NIL
        table.set("drop_item", method(table, args -> {
            double x = args.checkdouble(1);
            double y = args.checkdouble(2);
            double z = args.checkdouble(3);
            String materialName = args.checkjstring(4);
            int amount = args.optint(5, 1);
            Material material = Material.matchMaterial(materialName);
            if (material == null) return LuaValue.NIL;
            Location loc = new Location(world, x, y, z);
            Item dropped = world.dropItemNaturally(loc, new ItemStack(material, amount));
            return LuaEntityTable.build(dropped);
        }));

        // spawn_firework(x, y, z, colors_table, type, power)
        table.set("spawn_firework", method(table, args -> {
            double x = args.checkdouble(1);
            double y = args.checkdouble(2);
            double z = args.checkdouble(3);
            LuaTable colorsTable = args.checktable(4);
            String typeName = args.optjstring(5, "BALL");
            int power = args.optint(6, 1);

            Location loc = new Location(world, x, y, z);

            Bukkit.getScheduler().runTask(MagmaCore.getInstance().getRequestingPlugin(), () -> {
                Firework firework = (Firework) world.spawnEntity(loc, EntityType.FIREWORK_ROCKET);
                FireworkMeta meta = firework.getFireworkMeta();

                List<Color> colors = new ArrayList<>();
                for (int i = 1; i <= colorsTable.length(); i++) {
                    String colorName = colorsTable.get(i).tojstring().toUpperCase(Locale.ROOT);
                    try {
                        java.lang.reflect.Field field = Color.class.getField(colorName);
                        colors.add((Color) field.get(null));
                    } catch (Exception e) {
                        // Skip invalid colors
                    }
                }
                if (colors.isEmpty()) colors.add(Color.WHITE);

                FireworkEffect.Type effectType;
                try {
                    effectType = FireworkEffect.Type.valueOf(typeName.toUpperCase(Locale.ROOT));
                } catch (Exception e) {
                    effectType = FireworkEffect.Type.BALL;
                }

                FireworkEffect effect = FireworkEffect.builder()
                        .withColor(colors)
                        .with(effectType)
                        .trail(true)
                        .build();

                meta.addEffect(effect);
                meta.setPower(power);
                firework.setFireworkMeta(meta);
            });

            return LuaValue.NIL;
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
