package com.magmaguy.magmacore.ai;

import java.util.Objects;
import java.util.regex.Pattern;

public final class MindMemoryKey<T> {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");

    private final String identifier;
    private final Class<T> type;
    private final MindValueCodec<T> codec;

    private MindMemoryKey(String identifier, Class<T> type, MindValueCodec<T> codec) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Mind memory identifiers must be namespaced lower-case keys");
        }
        this.identifier = identifier;
        this.type = Objects.requireNonNull(type, "type");
        this.codec = codec;
    }

    public static <T> MindMemoryKey<T> transientKey(String identifier, Class<T> type) {
        return new MindMemoryKey<>(identifier, type, null);
    }

    public static <T> MindMemoryKey<T> persistentKey(
            String identifier,
            Class<T> type,
            MindValueCodec<T> codec) {
        return new MindMemoryKey<>(identifier, type, Objects.requireNonNull(codec, "codec"));
    }

    public String identifier() {
        return identifier;
    }

    public Class<T> type() {
        return type;
    }

    public boolean persistent() {
        return codec != null;
    }

    MindValueCodec<T> codec() {
        if (codec == null) throw new IllegalStateException(identifier + " is transient");
        return codec;
    }

    T requireType(Object value) {
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(
                    "Memory " + identifier + " requires " + type.getSimpleName());
        }
        return type.cast(value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MindMemoryKey<?> key && identifier.equals(key.identifier);
    }

    @Override
    public int hashCode() {
        return identifier.hashCode();
    }

    @Override
    public String toString() {
        return identifier;
    }
}
