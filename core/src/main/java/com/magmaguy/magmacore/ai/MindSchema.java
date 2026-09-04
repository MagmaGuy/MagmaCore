package com.magmaguy.magmacore.ai;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class MindSchema {
    private final Map<String, MindMemoryKey<?>> memories;

    private MindSchema(Map<String, MindMemoryKey<?>> memories) {
        this.memories = Collections.unmodifiableMap(new LinkedHashMap<>(memories));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static MindSchema empty() {
        return new MindSchema(Map.of());
    }

    public MindMemoryKey<?> memory(String identifier) {
        return memories.get(identifier);
    }

    public Collection<MindMemoryKey<?>> memories() {
        return memories.values();
    }

    public boolean accepts(MindMemoryKey<?> key) {
        MindMemoryKey<?> declared = memories.get(key.identifier());
        return declared != null && declared.type().equals(key.type());
    }

    public static final class Builder {
        private final Map<String, MindMemoryKey<?>> memories = new LinkedHashMap<>();

        public Builder memory(MindMemoryKey<?> key) {
            Objects.requireNonNull(key, "key");
            MindMemoryKey<?> previous = memories.putIfAbsent(key.identifier(), key);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate mind memory: " + key.identifier());
            }
            return this;
        }

        public MindSchema build() {
            return new MindSchema(memories);
        }
    }
}
