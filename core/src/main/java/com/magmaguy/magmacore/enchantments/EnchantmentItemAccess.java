package com.magmaguy.magmacore.enchantments;

import com.magmaguy.magmacore.scripting.tables.LuaItemDurabilityTable;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.luaj.vm2.LuaTable;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** A captured inventory position, never a search for another matching item in the player's hands. */
final class EnchantmentItemAccess {
    private final UUID actor;
    private final int slot;
    private final ItemStack expected;

    EnchantmentItemAccess(UUID actor, int slot, ItemStack expected) {
        if (slot < 0 || slot > 40) throw new IllegalArgumentException("Invalid player inventory slot");
        this.actor = actor;
        this.slot = slot;
        this.expected = expected.clone();
    }

    LuaTable table() {
        LuaTable table = new LuaTable();
        LuaItemDurabilityTable.attach(table, this::read, this::update);
        return table;
    }

    boolean isEquipped() {
        if (!(Bukkit.getEntity(actor) instanceof Player player) || read() == null) return false;
        return slot >= 36 || slot == player.getInventory().getHeldItemSlot();
    }

    private ItemStack read() {
        if (!(Bukkit.getEntity(actor) instanceof Player player) || !player.isOnline()) return null;
        ItemStack current = player.getInventory().getItem(slot);
        return sameItem(current) ? current.clone() : null;
    }

    private boolean update(UnaryOperator<ItemStack> change) {
        ItemStack current = read();
        if (current == null) return false;
        ItemStack changed = change.apply(current);
        if (changed == null) return false;
        Player player = (Player) Bukkit.getEntity(actor);
        // No callbacks or scheduled turn intervene between validation and this commit.
        player.getInventory().setItem(slot, changed);
        return true;
    }

    private boolean sameItem(ItemStack current) {
        if (current == null || current.getAmount() <= 0 || current.getType() != expected.getType()) return false;
        ItemStack first = current.clone(), second = expected.clone();
        clearDamage(first); clearDamage(second);
        return first.isSimilar(second);
    }

    private static void clearDamage(ItemStack item) {
        if (item.getItemMeta() instanceof Damageable meta) {
            meta.setDamage(0);
            item.setItemMeta(meta);
        }
    }
}
