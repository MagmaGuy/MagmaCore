package com.magmaguy.magmacore.scripting.tables;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

public class LuaLivingEntityTable {

    public static LuaTable build(LivingEntity entity) {
        LuaTable table = LuaEntityTable.build(entity);
        if (entity == null) return table;

        table.set("health", entity.getHealth());
        table.set("maximum_health", entity.getMaxHealth());
        table.set("name", entity.getName());
        table.set("is_alive", LuaValue.valueOf(!entity.isDead()));

        table.set("damage", LuaTableSupport.tableMethod(table, args -> {
            entity.damage(args.checkdouble(1));
            return LuaValue.NIL;
        }));

        table.set("push", LuaTableSupport.tableMethod(table, args -> {
            entity.setVelocity(entity.getVelocity().add(
                new Vector(args.checkdouble(1), args.checkdouble(2), args.checkdouble(3))));
            return LuaValue.NIL;
        }));

        table.set("set_facing", LuaTableSupport.tableMethod(table, args -> {
            entity.teleport(entity.getLocation().setDirection(
                new Vector(args.checkdouble(1), args.checkdouble(2), args.checkdouble(3))));
            return LuaValue.NIL;
        }));

        table.set("add_potion_effect", LuaTableSupport.tableMethod(table, args -> {
            PotionEffectType type = PotionEffectType.getByName(args.checkjstring(1));
            if (type != null)
                entity.addPotionEffect(new PotionEffect(type, args.checkint(2), args.checkint(3)));
            return LuaValue.NIL;
        }));

        table.set("remove_potion_effect", LuaTableSupport.tableMethod(table, args -> {
            PotionEffectType type = PotionEffectType.getByName(args.checkjstring(1));
            if (type != null) entity.removePotionEffect(type);
            return LuaValue.NIL;
        }));

        if (entity instanceof Player player) {
            table.set("send_message", LuaTableSupport.tableMethod(table, args -> {
                player.sendMessage(args.checkjstring(1));
                return LuaValue.NIL;
            }));
        }

        return table;
    }
}
