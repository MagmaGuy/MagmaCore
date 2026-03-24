package com.magmaguy.magmacore.scripting.tables;

import com.magmaguy.magmacore.MagmaCore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
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

            table.set("get_held_item", LuaTableSupport.tableMethod(table, args -> {
                ItemStack item = player.getInventory().getItemInMainHand();
                if (item.getType() == Material.AIR || item.getAmount() <= 0) return LuaValue.NIL;
                LuaTable itemTable = new LuaTable();
                itemTable.set("type", item.getType().name());
                itemTable.set("amount", item.getAmount());
                itemTable.set("display_name", item.getItemMeta() != null && item.getItemMeta().hasDisplayName()
                        ? item.getItemMeta().getDisplayName()
                        : item.getType().name());
                return itemTable;
            }));

            table.set("consume_held_item", LuaTableSupport.tableMethod(table, args -> {
                int amount = args.narg() >= 1 && !args.arg(1).isnil() ? args.checkint(1) : 1;
                Bukkit.getScheduler().runTask(MagmaCore.getInstance().getRequestingPlugin(), () -> {
                    ItemStack item = player.getInventory().getItemInMainHand();
                    if (item.getType() == Material.AIR) return;
                    int newAmount = item.getAmount() - amount;
                    if (newAmount <= 0)
                        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                    else
                        item.setAmount(newAmount);
                });
                return LuaValue.NIL;
            }));

            table.set("has_item", LuaTableSupport.tableMethod(table, args -> {
                String materialName = args.checkjstring(1);
                int amount = args.narg() >= 2 && !args.arg(2).isnil() ? args.checkint(2) : 1;
                Material material = Material.matchMaterial(materialName);
                if (material == null) return LuaValue.FALSE;
                return LuaValue.valueOf(player.getInventory().contains(material, amount));
            }));

            table.set("game_mode", player.getGameMode().name().toLowerCase());

            LuaPlayerUITable.addTo(table, player);
        }

        return table;
    }
}
