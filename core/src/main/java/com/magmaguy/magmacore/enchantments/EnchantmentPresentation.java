package com.magmaguy.magmacore.enchantments;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Owns only its generated lore prefix and glint override, preserving the host's other item data. */
final class EnchantmentPresentation {
    private static final NamespacedKey ROOT = EnchantmentItemData.key("enchantment_presentation");
    private static final NamespacedKey LINES = EnchantmentItemData.key("lines");
    private static final NamespacedKey LABELS = EnchantmentItemData.key("labels");
    private static final NamespacedKey NAME = EnchantmentItemData.key("name");
    private static final NamespacedKey MAX = EnchantmentItemData.key("max");
    private static final NamespacedKey CURSE = EnchantmentItemData.key("curse");
    private static final NamespacedKey ORIGINAL_GLINT = EnchantmentItemData.key("original_glint");
    private static final NamespacedKey APPLIED_GLINT = EnchantmentItemData.key("applied_glint");

    private EnchantmentPresentation() { }

    static void render(ItemMeta meta, Map<String, Integer> entries,
                       Function<String, EnchantmentItems.Resolved> resolver) {
        render(meta, entries, resolver, UnaryOperator.identity());
    }

    static void render(ItemMeta meta, Map<String, Integer> entries,
                       Function<String, EnchantmentItems.Resolved> resolver,
                       UnaryOperator<List<String>> rebuildHostLore) {
        PersistentDataContainer data = meta.getPersistentDataContainer();
        PersistentDataContainer old = data.get(ROOT, PersistentDataType.TAG_CONTAINER);
        if (data.has(ROOT) && old == null) throw new IllegalArgumentException("Malformed enchantment presentation record");
        List<String> lore = meta.hasLore() ? new ArrayList<>(Objects.requireNonNull(meta.getLore())) : new ArrayList<>();
        Boolean originalGlint = meta.hasEnchantmentGlintOverride() ? meta.getEnchantmentGlintOverride() : null;
        PersistentDataContainer oldLabels = null;
        if (old != null) {
            if (!Integer.valueOf(1).equals(old.get(EnchantmentItemData.VERSION, PersistentDataType.INTEGER)))
                throw new IllegalArgumentException("Unknown enchantment presentation version");
            List<String> prefix = old.get(LINES, PersistentDataType.LIST.strings());
            if (prefix == null || prefix.size() > EnchantmentItemData.MAX_ENTRIES || lore.size() < prefix.size()
                    || !lore.subList(0, prefix.size()).equals(prefix))
                throw new IllegalArgumentException("Generated enchantment lore was changed externally; rebuild from the host definition");
            lore = new ArrayList<>(lore.subList(prefix.size(), lore.size()));
            Boolean applied = decodeGlint(old.get(APPLIED_GLINT, PersistentDataType.INTEGER));
            if (Objects.equals(originalGlint, applied)) originalGlint = decodeGlint(old.get(ORIGINAL_GLINT, PersistentDataType.INTEGER));
            oldLabels = old.get(LABELS, PersistentDataType.TAG_CONTAINER);
        }
        lore = List.copyOf(rebuildHostLore.apply(List.copyOf(lore)));
        PersistentDataContainer labels = data.getAdapterContext().newPersistentDataContainer();
        List<String> lines = new ArrayList<>();
        for (var entry : new TreeMap<>(entries).entrySet()) {
            EnchantmentItems.Resolved resolved = resolver.apply(entry.getKey());
            EnchantmentDefinition definition = resolved == null ? null : resolved.definition();
            boolean available = resolved != null && resolved.available() && entry.getValue() <= definition.maxLevel();
            NamespacedKey key = Objects.requireNonNull(NamespacedKey.fromString(entry.getKey()));
            PersistentDataContainer prior = oldLabels == null ? null : oldLabels.get(key, PersistentDataType.TAG_CONTAINER);
            String name = definition != null ? definition.name() : prior == null ? null : prior.get(NAME, PersistentDataType.STRING);
            Integer maximum = definition != null ? definition.maxLevel() : prior == null ? null : prior.get(MAX, PersistentDataType.INTEGER);
            boolean curse = definition != null ? definition.curse() : prior != null && Byte.valueOf((byte) 1).equals(prior.get(CURSE, PersistentDataType.BYTE));
            if (name == null) name = "Unavailable enchantment";
            if (maximum == null) maximum = Integer.MAX_VALUE;
            lines.add("\u00a7r" + (curse ? "\u00a7c" : "\u00a77") + name
                    + (entry.getValue() == 1 && maximum == 1 ? "" : " " + level(entry.getValue()))
                    + (available ? "" : " \u00a78(inactive)"));
            PersistentDataContainer label = data.getAdapterContext().newPersistentDataContainer();
            label.set(NAME, PersistentDataType.STRING, name);
            label.set(MAX, PersistentDataType.INTEGER, maximum);
            label.set(CURSE, PersistentDataType.BYTE, (byte) (curse ? 1 : 0));
            labels.set(key, PersistentDataType.TAG_CONTAINER, label);
        }
        Boolean appliedGlint = originalGlint == null && !entries.isEmpty() ? Boolean.TRUE : originalGlint;
        meta.setEnchantmentGlintOverride(appliedGlint);
        List<String> combined = new ArrayList<>(lines);
        combined.addAll(lore);
        meta.setLore(combined.isEmpty() ? null : combined);
        if (entries.isEmpty()) { data.remove(ROOT); return; }
        PersistentDataContainer record = data.getAdapterContext().newPersistentDataContainer();
        record.set(EnchantmentItemData.VERSION, PersistentDataType.INTEGER, 1);
        // Bukkit normalizes legacy formatting when converting lore to native components.
        // Compare against that stored form on redraw, not our pre-conversion input.
        record.set(LINES, PersistentDataType.LIST.strings(),
                List.copyOf(Objects.requireNonNull(meta.getLore()).subList(0, lines.size())));
        record.set(LABELS, PersistentDataType.TAG_CONTAINER, labels);
        record.set(ORIGINAL_GLINT, PersistentDataType.INTEGER, encodeGlint(originalGlint));
        record.set(APPLIED_GLINT, PersistentDataType.INTEGER, encodeGlint(appliedGlint));
        data.set(ROOT, PersistentDataType.TAG_CONTAINER, record);
    }

    static String level(int level) {
        if (level < 1) throw new IllegalArgumentException("Level must be positive");
        if (level > 3999) return Integer.toString(level);
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] letters = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length; i++) while (level >= values[i]) { result.append(letters[i]); level -= values[i]; }
        return result.toString();
    }

    private static int encodeGlint(Boolean value) { return value == null ? -1 : value ? 1 : 0; }
    private static Boolean decodeGlint(Integer value) {
        if (value == null || value < -1 || value > 1) throw new IllegalArgumentException("Malformed glint ownership");
        return value == -1 ? null : value == 1;
    }
}
