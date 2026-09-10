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

    /** Optional supplied-effect target rule. Scripts remain free to use ordinary direct entity APIs. */
    public static boolean isHostileEffectTarget(Player actor, Entity target) {
        return actor != null && actor.isOnline() && actor.isValid() && !actor.isDead()
                && target instanceof org.bukkit.entity.LivingEntity && !(target instanceof Player)
                && !(target instanceof org.bukkit.entity.ArmorStand) && target.isValid() && !target.isDead()
                && !target.isInvulnerable() && !target.hasMetadata("NPC")
                && !(target instanceof org.bukkit.entity.Tameable tameable && tameable.isTamed())
                && actor.getWorld().equals(target.getWorld());
    }

    public static LuaTable build(Entity entity) {
        LuaTable table = new LuaTable();
        if (entity == null) return table;

        table.set("uuid", entity.getUniqueId().toString());
        table.set("entity_type", entity.getType().name().toLowerCase());
        LuaTableSupport.lazyField(table, "is_on_ground", () -> LuaValue.valueOf(entity.isOnGround()));
        LuaTableSupport.lazyField(table, "is_flying", () -> LuaValue.valueOf(entity instanceof Player player && player.isFlying()));
        table.set("set_velocity", LuaTableSupport.tableMethod(table, args -> {
            double x = args.checkdouble(1), y = args.checkdouble(2), z = args.checkdouble(3);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Velocity must be finite");
            entity.setVelocity(new org.bukkit.util.Vector(x, y, z));
            return LuaValue.NIL;
        }));
        table.set("set_fall_distance", LuaTableSupport.tableMethod(table, args -> {
            float distance = (float) args.checkdouble(1);
            if (!Float.isFinite(distance) || distance < 0) throw new IllegalArgumentException("Invalid fall distance");
            entity.setFallDistance(distance);
            return LuaValue.NIL;
        }));
        if (entity instanceof org.bukkit.entity.Projectile projectile) {
            table.set("set_shooter", LuaTableSupport.tableMethod(table, args -> {
                var shooter = org.bukkit.Bukkit.getEntity(java.util.UUID.fromString(args.checkjstring(1)));
                if (!(shooter instanceof org.bukkit.projectiles.ProjectileSource source)) return LuaValue.FALSE;
                projectile.setShooter(source);
                return LuaValue.TRUE;
            }));
        }
        if (entity instanceof org.bukkit.entity.Fireball fireball) {
            table.set("set_direction", LuaTableSupport.tableMethod(table, args -> {
                var direction = new org.bukkit.util.Vector(args.checkdouble(1), args.checkdouble(2), args.checkdouble(3));
                if (!Double.isFinite(direction.lengthSquared()) || direction.lengthSquared() == 0)
                    throw new IllegalArgumentException("Invalid fireball direction");
                fireball.setDirection(direction);
                return LuaValue.NIL;
            }));
        }
        if (entity instanceof org.bukkit.entity.AbstractArrow arrow) {
            LuaTableSupport.lazyField(table, "in_block", () -> LuaValue.valueOf(arrow.isInBlock()));
            LuaTableSupport.lazyField(table, "attachment_location", () -> arrow.getAttachedBlock() == null ? LuaValue.NIL
                    : LuaTableSupport.locationToTable(arrow.getAttachedBlock().getRelative(arrow.getFacing()).getLocation().add(.5, .5, .5)));
            table.set("set_pickup", LuaTableSupport.tableMethod(table, args -> {
                arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.valueOf(args.checkjstring(1).toUpperCase(java.util.Locale.ROOT)));
                return LuaValue.NIL;
            }));
        }
        table.set("can_receive_hostile_effect", LuaTableSupport.tableMethod(table, args -> {
            Entity actor = org.bukkit.Bukkit.getEntity(java.util.UUID.fromString(args.checkjstring(1)));
            return LuaValue.valueOf(actor instanceof Player player && isHostileEffectTarget(player, entity));
        }));
        table.set("has_clear_movement_path", LuaTableSupport.tableMethod(table, args ->
                LuaValue.valueOf(com.magmaguy.magmacore.util.EntityMovementPath.isClear(entity,
                        LuaTableSupport.tableToLocation(args.checktable(1), entity.getWorld())))));
        table.set("movement_path_status", LuaTableSupport.tableMethod(table, args ->
                LuaValue.valueOf(com.magmaguy.magmacore.util.EntityMovementPath.inspect(entity,
                        LuaTableSupport.tableToLocation(args.checktable(1), entity.getWorld())).name().toLowerCase(java.util.Locale.ROOT))));

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
