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
    /** expected with damage cleared and set lore stripped, built on first comparison; never changes after. */
    private ItemStack normalizedExpected;

    EnchantmentItemAccess(UUID actor, int slot, ItemStack expected) {
        if (slot < 0 || slot > 40) throw new IllegalArgumentException("Invalid player inventory slot");
        this.actor = actor;
        this.slot = slot;
        this.expected = expected.clone();
    }

    LuaTable table() {
        LuaTable table = new LuaTable();
        LuaItemDurabilityTable.attach(table, this::read, this::update);
        table.set("consume", com.magmaguy.magmacore.scripting.tables.LuaTableSupport.tableMethod(table, args -> {
            int amount = args.checkint(1);
            if (amount < 1) throw new IllegalArgumentException("Consumption must be positive");
            return org.luaj.vm2.LuaValue.valueOf(update(stack -> {
                if (stack.getAmount() < amount) return null;
                ItemStack changed = stack.clone();
                changed.setAmount(changed.getAmount() - amount);
                return changed;
            }));
        }));
        return table;
    }

    boolean isEquipped() {
        // Runs every tick for stop_on_unequip actions: one entity lookup, and no copy of a stack only compared.
        if (!(Bukkit.getEntity(actor) instanceof Player player) || !player.isOnline()
                || !sameItem(player.getInventory().getItem(slot))) return false;
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
        // Bukkit can still be processing this same stack (for example, melee wear
        // follows the damage event). Keep its native identity so that later vanilla
        // updates reach the equipped item instead of an abandoned replacement.
        ItemStack live = player.getInventory().getItem(slot);
        live.setAmount(changed.getAmount());
        if (changed.getAmount() > 0) live.setItemMeta(changed.getItemMeta());
        return true;
    }

    private boolean sameItem(ItemStack current) {
        if (current == null || current.getAmount() <= 0 || current.getType() != expected.getType()) return false;
        ItemStack first = current.clone();
        clearDamage(first);
        // Set lore follows what the player wears; a redraw does not make this a different item.
        ItemSets.strip(first);
        return first.isSimilar(normalizedExpected());
    }

    /** The expected stack never changes, so it is normalized once with the same steps as the current one. */
    private ItemStack normalizedExpected() {
        if (normalizedExpected == null) {
            ItemStack second = expected.clone();
            clearDamage(second);
            ItemSets.strip(second);
            normalizedExpected = second;
        }
        return normalizedExpected;
    }

    private static void clearDamage(ItemStack item) {
        if (item.getItemMeta() instanceof Damageable meta) {
            meta.setDamage(0);
            item.setItemMeta(meta);
        }
    }
}
