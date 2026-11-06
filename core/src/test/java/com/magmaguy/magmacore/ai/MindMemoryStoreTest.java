package com.magmaguy.magmacore.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MindMemoryStoreTest {
    private static final MindMemoryKey<String> TARGET = MindMemoryKey.transientKey(
            "test:target", String.class);
    private static final MindMemoryKey<Long> SCORE = MindMemoryKey.persistentKey(
            "test:score", Long.class, MindValueCodec.LONG);
    private static final MindSchema SCHEMA = MindSchema.builder()
            .memory(TARGET)
            .memory(SCORE)
            .build();

    @Test
    void expiresTransientMemoryAtItsTickDeadline() {
        MindMemoryStore store = new MindMemoryStore(SCHEMA);

        store.view(10).set(TARGET, "player", 3);

        assertEquals("player", store.view(12).get(TARGET).orElseThrow());
        assertFalse(store.view(13).contains(TARGET));
    }

    @Test
    void persistenceContainsOnlyCodecBackedMemory() {
        MindMemoryStore store = new MindMemoryStore(SCHEMA);
        store.view(1).set(TARGET, "player");
        store.view(1).set(SCORE, 42L);

        MindPersistentState state = store.persistentState();

        assertEquals(42L, state.memories().get("test:score"));
        assertFalse(state.memories().containsKey("test:target"));

        MindMemoryStore restored = new MindMemoryStore(SCHEMA);
        restored.restore(state);
        assertEquals(42L, restored.view(2).get(SCORE).orElseThrow());
        assertTrue(restored.view(2).get(TARGET).isEmpty());
    }

    @Test
    void pausedWorldTicksDoNotConsumeMemoryTtl() {
        MindMemoryStore store = new MindMemoryStore(SCHEMA);
        store.view(10).set(TARGET, "player", 3);

        store.shiftClock(100);

        assertEquals("player", store.view(112).get(TARGET).orElseThrow());
        assertFalse(store.view(113).contains(TARGET));
    }

    @Test
    void persistentStateIsReusedUntilAPersistentMemoryChanges() {
        MindMemoryStore store = new MindMemoryStore(SCHEMA);
        store.view(1).set(SCORE, 42L);
        MindPersistentState first = store.persistentState();

        store.view(2).set(TARGET, "player");
        store.view(3).set(SCORE, 42L);
        assertSame(first, store.persistentState(), "a later tick, a transient memory and the same value change nothing");

        store.view(4).set(SCORE, 43L);
        MindPersistentState changed = store.persistentState();
        assertNotSame(first, changed);
        assertEquals(43L, changed.memories().get("test:score"));

        store.view(5).forget(SCORE);
        assertFalse(store.persistentState().memories().containsKey("test:score"));
    }

    @Test
    void persistentStateCountsDownATtlEveryTick() {
        MindMemoryStore store = new MindMemoryStore(SCHEMA);
        store.view(1).set(SCORE, 42L, 10);
        assertEquals(10L, store.persistentState().remainingTtlTicks().get("test:score"));

        store.view(4);
        assertEquals(7L, store.persistentState().remainingTtlTicks().get("test:score"));

        store.view(11);
        assertTrue(store.persistentState().memories().isEmpty(), "expired at its deadline");
    }
}
