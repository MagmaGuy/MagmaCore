package com.magmaguy.magmacore.scripting.tables;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Shared durability operations; the host resolves and commits the actual item. */
public final class LuaItemDurabilityTable {
    private LuaItemDurabilityTable() { }

    public static void attach(LuaTable table, Supplier<ItemStack> read,
                              Function<UnaryOperator<ItemStack>, Boolean> update) {
        table.set("get_durability", LuaTableSupport.tableMethod(table, args -> {
            ItemStack item = read.get();
            if (!damageable(item)) return LuaValue.NIL;
            int maximum = item.getType().getMaxDurability();
            LuaTable value = new LuaTable();
            value.set("current", maximum - ((Damageable) item.getItemMeta()).getDamage());
            value.set("max", maximum);
            return value;
        }));
        table.set("get_durability_percentage", LuaTableSupport.tableMethod(table, args -> {
            ItemStack item = read.get();
            if (!damageable(item)) return LuaValue.NIL;
            int maximum = item.getType().getMaxDurability();
            return LuaValue.valueOf((double) (maximum - ((Damageable) item.getItemMeta()).getDamage()) / maximum);
        }));
        table.set("use_durability", LuaTableSupport.tableMethod(table, args -> {
            int amount = args.checkint(1);
            boolean canBreak = args.optboolean(2, false);
            return LuaValue.valueOf(update.apply(item -> damage(item, amount, canBreak)));
        }));
        table.set("use_durability_percentage", LuaTableSupport.tableMethod(table, args -> {
            double fraction = args.checkdouble(1);
            if (!Double.isFinite(fraction)) throw new IllegalArgumentException("Durability fraction must be finite");
            boolean canBreak = args.optboolean(2, false);
            return LuaValue.valueOf(update.apply(item -> {
                if (!damageable(item)) return null;
                double amount = Math.ceil(item.getType().getMaxDurability() * fraction);
                if (amount < Integer.MIN_VALUE || amount > Integer.MAX_VALUE)
                    throw new IllegalArgumentException("Durability amount is outside integer range");
                return damage(item, (int) amount, canBreak);
            }));
        }));
    }

    private static boolean damageable(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getType().getMaxDurability() > 0
                && item.getItemMeta() instanceof Damageable;
    }

    private static ItemStack damage(ItemStack item, int amount, boolean canBreak) {
        if (!damageable(item)) return null;
        ItemStack changed = item.clone();
        Damageable meta = (Damageable) changed.getItemMeta();
        int maximum = item.getType().getMaxDurability();
        long damage = (long) meta.getDamage() + amount;
        if (damage >= maximum && canBreak) changed.setAmount(0);
        else {
            meta.setDamage((int) Math.max(0, Math.min(maximum - 1L, damage)));
            changed.setItemMeta(meta);
        }
        return changed;
    }
}
