package com.magmaguy.magmacore.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versioned, plugin-owned memory payload. Values are codec-encoded scalars.
 */
public record MindPersistentState(
        int formatVersion,
        Map<String, Object> memories,
        Map<String, Long> remainingTtlTicks) {
    public static final int CURRENT_FORMAT = 1;

    public MindPersistentState {
        if (formatVersion < 1) throw new IllegalArgumentException("formatVersion must be positive");
        memories = Collections.unmodifiableMap(new LinkedHashMap<>(memories));
        remainingTtlTicks = Collections.unmodifiableMap(new LinkedHashMap<>(remainingTtlTicks));
        for (Map.Entry<String, Long> entry : remainingTtlTicks.entrySet()) {
            if (!memories.containsKey(entry.getKey()) || entry.getValue() <= 0L) {
                throw new IllegalArgumentException("TTL entries require a matching memory and positive duration");
            }
        }
    }

    public MindPersistentState(int formatVersion, Map<String, Object> memories) {
        this(formatVersion, memories, Map.of());
    }

    public static MindPersistentState empty() {
        return new MindPersistentState(CURRENT_FORMAT, Map.of(), Map.of());
    }
}
