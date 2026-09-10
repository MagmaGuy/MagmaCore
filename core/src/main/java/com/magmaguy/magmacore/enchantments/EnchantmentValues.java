package com.magmaguy.magmacore.enchantments;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Copies the bounded JDK/Bukkit values allowed across independently shaded providers. */
final class EnchantmentValues {
    private static final int MAX_NODES = 2048;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_STRING = 8192;

    private EnchantmentValues() { }

    static Map<String, Object> copy(Map<?, ?> value) {
        if (value == null) throw new IllegalArgumentException("Missing payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) copy(value, 0, new int[]{0});
        return result;
    }

    private static Object copy(Object value, int depth, int[] count) {
        if (++count[0] > MAX_NODES || depth > MAX_DEPTH)
            throw new IllegalArgumentException("Enchantment payload exceeds structural limits");
        if (value instanceof String string) {
            if (string.length() > MAX_STRING) throw new IllegalArgumentException("Payload string too long");
            return string;
        }
        if (value instanceof Boolean || value instanceof UUID || value instanceof Integer
                || value instanceof Long || value instanceof Short || value instanceof Byte) return value;
        if (value instanceof Double number && Double.isFinite(number)) return number;
        if (value instanceof Float number && Float.isFinite(number)) return number;
        if (value instanceof ItemStack item) return item.clone();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.length() > 128)
                    throw new IllegalArgumentException("Payload keys must be strings of at most 128 characters");
                result.put(key, copy(entry.getValue(), depth + 1, count));
            }
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object entry : list) result.add(copy(entry, depth + 1, count));
            return Collections.unmodifiableList(result);
        }
        throw new IllegalArgumentException("Unsupported enchantment payload value");
    }
}
