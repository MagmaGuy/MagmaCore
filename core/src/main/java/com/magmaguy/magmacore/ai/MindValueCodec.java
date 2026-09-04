package com.magmaguy.magmacore.ai;

import java.util.UUID;

/**
 * Converts plugin-owned mind memory to persistence-safe scalar values.
 */
public interface MindValueCodec<T> {
    MindValueCodec<String> STRING = scalar(String.class);
    MindValueCodec<Boolean> BOOLEAN = scalar(Boolean.class);
    MindValueCodec<Long> LONG = new MindValueCodec<>() {
        @Override
        public Object encode(Long value) {
            return value;
        }

        @Override
        public Long decode(Object value) {
            if (value instanceof Number number) return number.longValue();
            throw new IllegalArgumentException("Expected a numeric persisted value");
        }
    };
    MindValueCodec<Double> DOUBLE = new MindValueCodec<>() {
        @Override
        public Object encode(Double value) {
            return value;
        }

        @Override
        public Double decode(Object value) {
            if (value instanceof Number number) return number.doubleValue();
            throw new IllegalArgumentException("Expected a numeric persisted value");
        }
    };
    MindValueCodec<UUID> UUID = new MindValueCodec<>() {
        @Override
        public Object encode(java.util.UUID value) {
            return value.toString();
        }

        @Override
        public java.util.UUID decode(Object value) {
            if (value instanceof String string) return java.util.UUID.fromString(string);
            throw new IllegalArgumentException("Expected a UUID string");
        }
    };

    Object encode(T value);

    T decode(Object value);

    static <T> MindValueCodec<T> scalar(Class<T> type) {
        return new MindValueCodec<>() {
            @Override
            public Object encode(T value) {
                return value;
            }

            @Override
            public T decode(Object value) {
                if (type.isInstance(value)) return type.cast(value);
                throw new IllegalArgumentException("Expected persisted value of type " + type.getSimpleName());
            }
        };
    }
}
