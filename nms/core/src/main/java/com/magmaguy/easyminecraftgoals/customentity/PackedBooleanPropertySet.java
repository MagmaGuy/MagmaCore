package com.magmaguy.easyminecraftgoals.customentity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PackedBooleanPropertySet {
    /**
     * How many flags one int property carries. A property with every flag set is 2^19 - 1 = 524,287, which stays
     * inside the range of -1,000,000 to 1,000,000 that ResourcePackManager's Geyser bridge registers every int
     * property with; a 20th flag would reach 1,048,575, past it. Whatever unpacks the flags on the client must use
     * this constant too.
     */
    public static final int BITS_PER_PROPERTY = 19;

    private PackedBooleanPropertySet() {
    }

    public static int requiredPropertyCount(int booleanCount) {
        if (booleanCount <= 0) return 0;
        return (int) Math.ceil(booleanCount / (double) BITS_PER_PROPERTY);
    }

    public static List<Integer> packBooleans(Collection<Boolean> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<Integer> packed = new ArrayList<>(requiredPropertyCount(values.size()));
        int current = 0;
        int bit = 0;
        for (Boolean value : values) {
            if (Boolean.TRUE.equals(value)) {
                current |= 1 << bit;
            }
            bit++;
            if (bit == BITS_PER_PROPERTY) {
                packed.add(current);
                current = 0;
                bit = 0;
            }
        }
        if (bit != 0) {
            packed.add(current);
        }
        return packed;
    }

    public static Map<String, Integer> packNamedBooleans(List<String> orderedNames,
                                                        Map<String, Boolean> states,
                                                        String propertyPrefix) {
        if (orderedNames == null || orderedNames.isEmpty()) return Map.of();
        List<Boolean> orderedValues = new ArrayList<>(orderedNames.size());
        for (String name : orderedNames) {
            orderedValues.add(states != null && Boolean.TRUE.equals(states.get(name)));
        }

        List<Integer> packed = packBooleans(orderedValues);
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < packed.size(); i++) {
            result.put(propertyPrefix + i, packed.get(i));
        }
        return result;
    }
}
