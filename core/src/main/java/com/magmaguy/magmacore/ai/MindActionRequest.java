package com.magmaguy.magmacore.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable, version-independent request for a host-owned semantic action.
 *
 * <p>Payload values are limited to strings, booleans, integral and floating-point numbers,
 * UUIDs and {@link MindPosition} values. Numeric inputs are normalized to {@link Long} or
 * {@link Double}. Collections, Bukkit objects and plugin-owned objects are rejected.</p>
 */
public record MindActionRequest(String identifier, Map<String, ?> payload) {
    public static final int MAX_IDENTIFIER_LENGTH = 128;
    public static final int MAX_PAYLOAD_ENTRIES = 16;
    public static final int MAX_PAYLOAD_KEY_LENGTH = 64;
    public static final int MAX_STRING_LENGTH = 256;
    public static final int MAX_PAYLOAD_WEIGHT = 2_048;

    private static final Pattern IDENTIFIER =
            Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");
    private static final Pattern PAYLOAD_KEY = Pattern.compile("[a-z0-9._-]+");

    public MindActionRequest {
        identifier = validateIdentifier(identifier);
        payload = normalizePayload(payload);
    }

    private static String validateIdentifier(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (identifier.length() > MAX_IDENTIFIER_LENGTH || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    "Mind action identifier must be a namespaced lower-case key of at most "
                            + MAX_IDENTIFIER_LENGTH + " characters");
        }
        return identifier;
    }

    private static Map<String, Object> normalizePayload(Map<String, ?> payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.size() > MAX_PAYLOAD_ENTRIES) {
            throw new IllegalArgumentException(
                    "Mind action payload cannot exceed " + MAX_PAYLOAD_ENTRIES + " entries");
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        int weight = 0;
        for (Map.Entry<String, ?> entry : payload.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "payload key");
            if (key.length() > MAX_PAYLOAD_KEY_LENGTH || !PAYLOAD_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException(
                        "Mind action payload keys must be lower-case identifiers of at most "
                                + MAX_PAYLOAD_KEY_LENGTH + " characters: " + key);
            }
            Object value = normalizeValue(key, entry.getValue());
            weight = Math.addExact(weight, key.length() + valueWeight(value));
            if (weight > MAX_PAYLOAD_WEIGHT) {
                throw new IllegalArgumentException(
                        "Mind action payload exceeds the bounded weight of " + MAX_PAYLOAD_WEIGHT);
            }
            normalized.put(key, value);
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static Object normalizeValue(String key, Object value) {
        Objects.requireNonNull(value, "payload value for " + key);
        if (value instanceof String string) {
            if (string.length() > MAX_STRING_LENGTH) {
                throw new IllegalArgumentException(
                        "Mind action string payload cannot exceed " + MAX_STRING_LENGTH + " characters: " + key);
            }
            return string;
        }
        if (value instanceof Boolean || value instanceof UUID) return value;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("Mind action numeric payload must be finite: " + key);
            }
            return number;
        }
        if (value instanceof MindPosition position) {
            if (position.world().length() > MAX_STRING_LENGTH) {
                throw new IllegalArgumentException(
                        "Mind action position world cannot exceed " + MAX_STRING_LENGTH + " characters: " + key);
            }
            return position;
        }
        throw new IllegalArgumentException(
                "Unsupported Mind action payload type for " + key + ": " + value.getClass().getName());
    }

    private static int valueWeight(Object value) {
        if (value instanceof String string) return string.length();
        if (value instanceof Boolean) return 1;
        if (value instanceof Long || value instanceof Double) return 8;
        if (value instanceof UUID) return 16;
        MindPosition position = (MindPosition) value;
        return 24 + position.world().length();
    }
}
