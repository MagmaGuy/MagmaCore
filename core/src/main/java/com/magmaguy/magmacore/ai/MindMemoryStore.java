package com.magmaguy.magmacore.ai;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class MindMemoryStore {
    private final MindSchema schema;
    private final Map<MindMemoryKey<?>, Entry> values = new LinkedHashMap<>();
    private long currentTick;
    private MindPersistentState pendingRestore;

    public MindMemoryStore(MindSchema schema) {
        this.schema = schema;
    }

    public MindMemoryView view(long gameTick) {
        currentTick = gameTick;
        materializePendingRestore(gameTick);
        expire(gameTick);
        return new View();
    }

    public MindMemoryStore transferTo(MindSchema nextSchema, StateTransfer transfer) {
        materializePendingRestore(currentTick);
        MindMemoryStore next = new MindMemoryStore(nextSchema);
        if (transfer == StateTransfer.NONE) return next;

        for (Map.Entry<MindMemoryKey<?>, Entry> entry : values.entrySet()) {
            MindMemoryKey<?> oldKey = entry.getKey();
            MindMemoryKey<?> nextKey = nextSchema.memory(oldKey.identifier());
            if (nextKey == null || !nextKey.type().equals(oldKey.type())) continue;
            if (transfer == StateTransfer.PERSISTENT_ONLY
                    && (!oldKey.persistent() || !nextKey.persistent())) continue;
            next.values.put(nextKey, entry.getValue());
        }
        next.currentTick = currentTick;
        return next;
    }

    /**
     * Advances the store's clock without consuming any finite TTL.
     *
     * <p>Mind hosts use this when world time advanced while a logical Mind was paused. Expiry
     * deadlines shift by the same saturated duration, preserving every remaining active tick.</p>
     */
    public void shiftClock(long elapsedTicks) {
        if (elapsedTicks < 0L) throw new IllegalArgumentException("elapsedTicks cannot be negative");
        if (elapsedTicks == 0L) return;
        if (pendingRestore == null) {
            values.replaceAll((ignored, entry) -> new Entry(
                    entry.value,
                    entry.expiresAtTick == Long.MAX_VALUE
                            ? Long.MAX_VALUE
                            : saturatedAdd(entry.expiresAtTick, elapsedTicks)));
        }
        currentTick = saturatedAdd(currentTick, elapsedTicks);
    }

    public MindPersistentState persistentState() {
        if (pendingRestore != null) return pendingRestore;
        expire(currentTick);
        Map<String, Object> encoded = new LinkedHashMap<>();
        Map<String, Long> remainingTtl = new LinkedHashMap<>();
        for (Map.Entry<MindMemoryKey<?>, Entry> entry : values.entrySet()) {
            MindMemoryKey<?> key = entry.getKey();
            if (!key.persistent()) continue;
            encoded.put(key.identifier(), encode(key, entry.getValue().value));
            if (entry.getValue().expiresAtTick != Long.MAX_VALUE) {
                remainingTtl.put(
                        key.identifier(),
                        Math.max(1L, entry.getValue().expiresAtTick - currentTick));
            }
        }
        return new MindPersistentState(MindPersistentState.CURRENT_FORMAT, encoded, remainingTtl);
    }

    public void restore(MindPersistentState state) {
        if (state.formatVersion() != MindPersistentState.CURRENT_FORMAT) {
            throw new IllegalArgumentException(
                    "Unsupported mind state format " + state.formatVersion());
        }
        values.clear();
        pendingRestore = state;
    }

    private void materializePendingRestore(long gameTick) {
        if (pendingRestore == null) return;
        MindPersistentState state = pendingRestore;
        pendingRestore = null;
        for (Map.Entry<String, Object> encoded : state.memories().entrySet()) {
            MindMemoryKey<?> key = schema.memory(encoded.getKey());
            if (key == null || !key.persistent()) continue;
            Long remaining = state.remainingTtlTicks().get(encoded.getKey());
            long expiresAt = remaining == null
                    ? Long.MAX_VALUE
                    : gameTick > Long.MAX_VALUE - remaining
                            ? Long.MAX_VALUE
                            : gameTick + remaining;
            values.put(key, new Entry(decode(key, encoded.getValue()), expiresAt));
        }
    }

    private void expire(long gameTick) {
        Iterator<Entry> iterator = values.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.expiresAtTick <= gameTick) iterator.remove();
        }
    }

    private static long saturatedAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    @SuppressWarnings("unchecked")
    private static <T> Object encode(MindMemoryKey<T> key, Object value) {
        return key.codec().encode(key.requireType(value));
    }

    @SuppressWarnings("unchecked")
    private static <T> Object decode(MindMemoryKey<T> key, Object value) {
        return key.requireType(key.codec().decode(value));
    }

    private record Entry(Object value, long expiresAtTick) {
    }

    private final class View implements MindMemoryView {
        @Override
        public <T> Optional<T> get(MindMemoryKey<T> key) {
            requireDeclared(key);
            Entry entry = values.get(key);
            if (entry == null) return Optional.empty();
            return Optional.of(key.requireType(entry.value));
        }

        @Override
        public <T> void set(MindMemoryKey<T> key, T value) {
            put(key, value, Long.MAX_VALUE);
        }

        @Override
        public <T> void set(MindMemoryKey<T> key, T value, long ttlTicks) {
            if (ttlTicks <= 0) throw new IllegalArgumentException("ttlTicks must be positive");
            long expiresAt = currentTick > Long.MAX_VALUE - ttlTicks
                    ? Long.MAX_VALUE
                    : currentTick + ttlTicks;
            put(key, value, expiresAt);
        }

        @Override
        public void forget(MindMemoryKey<?> key) {
            requireDeclared(key);
            values.remove(key);
        }

        @Override
        public boolean contains(MindMemoryKey<?> key) {
            requireDeclared(key);
            return values.containsKey(key);
        }

        private <T> void put(MindMemoryKey<T> key, T value, long expiresAtTick) {
            requireDeclared(key);
            values.put(key, new Entry(key.requireType(value), expiresAtTick));
        }

        private void requireDeclared(MindMemoryKey<?> key) {
            if (!schema.accepts(key)) {
                throw new IllegalArgumentException("Memory is not declared by this mind: " + key.identifier());
            }
        }
    }
}
