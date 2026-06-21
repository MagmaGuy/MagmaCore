package com.magmaguy.easyminecraftgoals.customentity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CustomEntityPropertyState {
    private final Map<String, Object> values = new LinkedHashMap<>();

    public CustomEntityPropertyState set(String identifier, Object value) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Property identifier cannot be blank");
        }
        if (value == null) {
            values.remove(identifier);
        } else {
            values.put(identifier, value);
        }
        return this;
    }

    public CustomEntityPropertyState setBoolean(String identifier, boolean value) {
        return set(identifier, value);
    }

    public CustomEntityPropertyState setInt(String identifier, int value) {
        return set(identifier, value);
    }

    public CustomEntityPropertyState setFloat(String identifier, float value) {
        return set(identifier, value);
    }

    public CustomEntityPropertyState setString(String identifier, String value) {
        return set(identifier, value);
    }

    public CustomEntityPropertyState setAll(Map<String, ?> updates) {
        if (updates == null) return this;
        updates.forEach(this::set);
        return this;
    }

    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
