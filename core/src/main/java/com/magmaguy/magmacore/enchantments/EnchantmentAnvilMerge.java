package com.magmaguy.magmacore.enchantments;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.*;

/** Shared custom-book arithmetic and validation; the anvil listener owns native pricing and inventory pickup. */
public final class EnchantmentAnvilMerge {
    private final EnchantmentItems items;
    public EnchantmentAnvilMerge(EnchantmentItems items) { this.items = Objects.requireNonNull(items); }

    public Preview prepare(ItemStack source, ItemStack book, ItemStack nativeResult, int baseCost) {
        if (source == null || book == null || book.getType() != Material.ENCHANTED_BOOK || baseCost < 0)
            throw new IllegalArgumentException("Expected an item, actual enchanted book and nonnegative native cost");
        var original = items.inspect(source);
        var additions = items.inspect(book);
        var sourceValidation = items.preview(source, original);
        var bookValidation = items.preview(book, additions);
        if (additions.isEmpty()) throw new IllegalArgumentException("Book has no transferable enchantments");
        var custom = new LinkedHashMap<String,Integer>();
        original.forEach((id, level) -> { if (!id.startsWith("minecraft:")) custom.put(id, level); });
        boolean containsCustom = !custom.isEmpty() || additions.keySet().stream().anyMatch(id -> !id.startsWith("minecraft:"));
        if (!containsCustom) throw new IllegalArgumentException("Native-only operation belongs to the server");
        int additionalCost = 0;
        for (var entry : additions.entrySet()) {
            if (entry.getKey().startsWith("minecraft:")) continue;
            int previous = custom.getOrDefault(entry.getKey(), 0);
            int next = previous == entry.getValue() ? Math.addExact(previous, 1) : Math.max(previous, entry.getValue());
            custom.put(entry.getKey(), next);
            if (next != previous) additionalCost = Math.addExact(additionalCost, next);
        }
        ItemStack draft = nativeResult == null ? source.clone() : nativeResult.clone();
        draft.setAmount(1);
        var resultNatives = items.inspect(draft);
        // A mixed book must not silently lose a native entry the server refused to apply.
        for (var entry : additions.entrySet()) {
            if (!entry.getKey().startsWith("minecraft:")) continue;
            int old = original.getOrDefault(entry.getKey(), 0);
            int expected = old == entry.getValue() ? Math.addExact(old, 1) : Math.max(old, entry.getValue());
            var nativeEnchantment = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.fromString(entry.getKey()));
            if (nativeEnchantment == null) throw new IllegalArgumentException("Unknown native enchantment");
            expected = Math.min(expected, nativeEnchantment.getMaxLevel());
            if (nativeResult == null || resultNatives.getOrDefault(entry.getKey(), 0) != expected)
                throw new IllegalArgumentException("The server rejected a native book entry: " + entry.getKey());
        }
        var complete = new LinkedHashMap<String,Integer>();
        resultNatives.forEach((id, level) -> { if (id.startsWith("minecraft:")) complete.put(id, level); });
        complete.putAll(custom);
        // Validate all resulting native conflicts and item restrictions as well as the custom set.
        items.preview(draft, complete);
        var result = items.previewCustom(draft, custom);
        if (additionalCost == 0 && nativeResult == null)
            throw new IllegalArgumentException("Book makes no transferable change");
        int cost = Math.addExact(baseCost, additionalCost);
        return new Preview(source.clone(), book.clone(), draft, sourceValidation, bookValidation, result, cost);
    }

    public static final class Preview {
        private final ItemStack source, book, draft;
        private final EnchantmentItems.Preview sourceValidation, bookValidation, result;
        private final int cost;
        private Preview(ItemStack source, ItemStack book, ItemStack draft, EnchantmentItems.Preview sourceValidation,
                        EnchantmentItems.Preview bookValidation, EnchantmentItems.Preview result, int cost) {
            this.source = source; this.book = book; this.draft = draft;
            this.sourceValidation = sourceValidation; this.bookValidation = bookValidation;
            this.result = result; this.cost = cost;
        }
        public int cost() { return cost; }
        public ItemStack item() { return result.previewItem(); }
        public ItemStack apply(ItemStack source, ItemStack book) {
            if (!this.source.equals(source) || !this.book.equals(book))
                throw new ConcurrentModificationException("Anvil inputs changed after preview");
            sourceValidation.apply(source);
            bookValidation.apply(book);
            return result.apply(draft);
        }
    }
}
