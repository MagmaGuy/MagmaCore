package com.magmaguy.easyminecraftgoals.customentity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackedBooleanPropertySetTest {
    @Test
    void packsNineteenBooleansPerProperty() {
        List<Boolean> booleans = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            booleans.add(i == 0 || i == 18 || i == 19);
        }

        List<Integer> packed = PackedBooleanPropertySet.packBooleans(booleans);

        assertEquals(2, packed.size());
        assertEquals(1 | (1 << 18), packed.get(0));
        assertEquals(1, packed.get(1));
    }

    @Test
    void aPropertyWithEveryFlagSetStaysInsideTheBridgesIntRange() {
        // ResourcePackManager's Geyser bridge registers each int property as -1,000,000 to 1,000,000.
        List<Boolean> booleans = new ArrayList<>();
        for (int i = 0; i < 3 * PackedBooleanPropertySet.BITS_PER_PROPERTY; i++) booleans.add(true);

        List<Integer> packed = PackedBooleanPropertySet.packBooleans(booleans);

        assertEquals(3, packed.size());
        for (int value : packed) assertEquals(524_287, value);
        assertEquals(1, PackedBooleanPropertySet.requiredPropertyCount(19));
        assertEquals(2, PackedBooleanPropertySet.requiredPropertyCount(20));
    }

    @Test
    void packsNamedBooleansWithStablePrefixes() {
        List<String> names = List.of("head", "body", "tail");
        Map<String, Boolean> state = new LinkedHashMap<>();
        state.put("head", true);
        state.put("tail", true);

        Map<String, Integer> packed = PackedBooleanPropertySet.packNamedBooleans(
                names, state, "fmm:bone");

        assertEquals(Map.of("fmm:bone0", 5), packed);
    }

    @Test
    void schemaRejectsMoreThanThirtyTwoProperties() {
        CustomEntityPropertySchema.Builder builder = CustomEntityPropertySchema.builder();
        for (int i = 0; i < CustomEntityPropertySchema.MAX_PROPERTIES; i++) {
            builder.addInt("fmm:test" + i);
        }

        assertThrows(IllegalStateException.class, () -> builder.addInt("fmm:overflow"));
    }
}
