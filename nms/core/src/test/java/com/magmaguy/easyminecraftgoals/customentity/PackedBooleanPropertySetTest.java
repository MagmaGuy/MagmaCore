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
    void packsTwentyFourBooleansPerProperty() {
        List<Boolean> booleans = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            booleans.add(i == 0 || i == 23 || i == 24);
        }

        List<Integer> packed = PackedBooleanPropertySet.packBooleans(booleans);

        assertEquals(2, packed.size());
        assertEquals(1 | (1 << 23), packed.get(0));
        assertEquals(1, packed.get(1));
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
