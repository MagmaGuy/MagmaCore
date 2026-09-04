package com.magmaguy.magmacore.ai;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MindActionContractTest {
    @Test
    void normalizesTheDocumentedPersistenceSafePayloadTypes() {
        UUID target = UUID.randomUUID();
        MindPosition position = new MindPosition("world", 1.0D, 2.0D, 3.0D);
        MindActionRequest request = new MindActionRequest(
                "test:dig_block",
                Map.of(
                        "attempts", 2,
                        "target", target,
                        "position", position));

        assertEquals(2L, request.payload().get("attempts"));
        assertEquals(target, request.payload().get("target"));
        assertEquals(position, request.payload().get("position"));
        assertThrows(UnsupportedOperationException.class,
                () -> request.payload().clear());
    }

    @Test
    void rejectsPluginOwnedPayloadObjects() {
        assertThrows(IllegalArgumentException.class, () -> new MindActionRequest(
                "test:invalid",
                Map.of("object", new Object())));
    }

    @Test
    void legacyExecutionPolicyConstructorRetainsTheDefaultActionCap() {
        MindExecutionPolicy policy = new MindExecutionPolicy(1L, 2L, 3L, 4, 5L);

        assertEquals(
                MindExecutionPolicy.DEFAULT.maxActionRequestsPerEntityTick(),
                policy.maxActionRequestsPerEntityTick());
        assertEquals(5L, policy.maxLuaInstructionsPerCallback());
        assertEquals(
                MindRunawayPolicy.DEFAULT.maxCpuNanosPerCallback(),
                policy.runawayPolicy().maxCpuNanosPerCallback());
    }

    @Test
    void actionRequestCapCannotDisableTheNativeHardLimit() {
        assertThrows(IllegalArgumentException.class, () -> new MindExecutionPolicy(
                1L,
                2L,
                3L,
                4,
                5L,
                MindExecutionPolicy.ACTION_REQUESTS_PER_ENTITY_TICK_HARD_LIMIT + 1));
    }
}
