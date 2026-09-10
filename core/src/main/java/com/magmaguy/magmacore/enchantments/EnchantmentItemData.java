package com.magmaguy.magmacore.enchantments;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** The only custom identity/level encoding. Old plugin-specific records are deliberately not read. */
final class EnchantmentItemData {
    static final int MAX_ENTRIES = 64;
    static final NamespacedKey ROOT = key("enchantments");
    static final NamespacedKey VERSION = key("version");
    static final NamespacedKey ENTRIES = key("entries");
    static final NamespacedKey ID = key("id");
    static final NamespacedKey LEVEL = key("level");

    private EnchantmentItemData() { }
    static NamespacedKey key(String value) { return new NamespacedKey("magmacore", value); }

    static Map<String, Integer> read(ItemMeta meta) {
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (!data.has(ROOT)) return Map.of();
        PersistentDataContainer record = data.get(ROOT, PersistentDataType.TAG_CONTAINER);
        if (record == null || !record.getKeys().equals(Set.of(VERSION, ENTRIES))
                || !Integer.valueOf(1).equals(record.get(VERSION, PersistentDataType.INTEGER)))
            throw new IllegalArgumentException("Unknown or malformed custom-enchantment record");
        PersistentDataContainer[] entries = record.get(ENTRIES, PersistentDataType.TAG_CONTAINER_ARRAY);
        if (entries == null || entries.length > MAX_ENTRIES) throw new IllegalArgumentException("Invalid enchantment entries");
        Map<String, Integer> result = new LinkedHashMap<>();
        for (PersistentDataContainer entry : entries) {
            if (!entry.getKeys().equals(Set.of(ID, LEVEL))) throw new IllegalArgumentException("Malformed enchantment entry");
            String id = entry.get(ID, PersistentDataType.STRING);
            Integer level = entry.get(LEVEL, PersistentDataType.INTEGER);
            requireEntry(id, level);
            if (result.putIfAbsent(id, level) != null) throw new IllegalArgumentException("Duplicate enchantment identity: " + id);
        }
        return Map.copyOf(result);
    }

    static void write(ItemMeta meta, Map<String, Integer> entries) {
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many custom enchantments");
        entries.forEach(EnchantmentItemData::requireEntry);
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (entries.isEmpty()) { data.remove(ROOT); return; }
        PersistentDataContainer record = data.getAdapterContext().newPersistentDataContainer();
        record.set(VERSION, PersistentDataType.INTEGER, 1);
        PersistentDataContainer[] encoded = new PersistentDataContainer[entries.size()];
        int index = 0;
        for (var entry : new TreeMap<>(entries).entrySet()) {
            PersistentDataContainer row = data.getAdapterContext().newPersistentDataContainer();
            row.set(ID, PersistentDataType.STRING, entry.getKey());
            row.set(LEVEL, PersistentDataType.INTEGER, entry.getValue());
            encoded[index++] = row;
        }
        record.set(ENTRIES, PersistentDataType.TAG_CONTAINER_ARRAY, encoded);
        data.set(ROOT, PersistentDataType.TAG_CONTAINER, record);
    }

    private static void requireEntry(String id, Integer level) {
        EnchantmentDefinition.requireId(id);
        if (id.startsWith("minecraft:") || level == null || level < 1)
            throw new IllegalArgumentException("Invalid custom enchantment identity/level");
    }
}
