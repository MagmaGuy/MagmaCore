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
        var original = EnchantmentItems.inspectCustom(source.getItemMeta());
        var additions = EnchantmentItems.inspectCustom(book.getItemMeta());
        EnchantmentItems.validateAnvilNatives(source);
        EnchantmentItems.validateAnvilNatives(book);
        var sourceValidation = items.previewCustom(source, original);
        var bookValidation = items.previewCustom(book, additions);
        var nativeAdditions = EnchantmentItems.nativeEnchantments(book.getItemMeta());
        if (additions.isEmpty() && nativeAdditions.isEmpty()) throw new IllegalArgumentException("Book has no transferable enchantments");
        var custom = new LinkedHashMap<String,Integer>(original);
        boolean containsCustom = !custom.isEmpty() || !additions.isEmpty();
        if (!containsCustom) throw new IllegalArgumentException("Native-only operation belongs to the server");
        int additionalCost = 0;
        for (var entry : additions.entrySet()) {
            int previous = custom.getOrDefault(entry.getKey(), 0);
            int next = previous == entry.getValue() ? Math.addExact(previous, 1) : Math.max(previous, entry.getValue());
            custom.put(entry.getKey(), next);
            if (next != previous) additionalCost = Math.addExact(additionalCost, next);
        }
        ItemStack draft = nativeResult == null ? source.clone() : nativeResult.clone();
        draft.setAmount(1);
        var resultNatives = EnchantmentItems.nativeEnchantments(draft.getItemMeta());
        var sourceNatives = EnchantmentItems.nativeEnchantments(source.getItemMeta());
        // A mixed book must not silently lose a native entry the server refused to apply.
        for (var entry : nativeAdditions.entrySet()) {
            int old = sourceNatives.getOrDefault(entry.getKey(), 0);
            int expected = old == entry.getValue() ? Math.addExact(old, 1) : Math.max(old, entry.getValue());
            var nativeEnchantment = entry.getKey();
            expected = Math.min(expected, nativeEnchantment.getMaxLevel());
            int actual = resultNatives.getOrDefault(entry.getKey(), 0);
            boolean accepted = nativeEnchantment.getKey().getNamespace().equals("minecraft")
                    ? actual == expected
                    : actual >= Math.min(Math.max(old, entry.getValue()), nativeEnchantment.getMaxLevel());
            if (nativeResult == null || !accepted)
                throw new IllegalArgumentException("The server rejected a native book entry: " + entry.getKey());
        }
        EnchantmentItems.validateAnvilNatives(draft);
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
