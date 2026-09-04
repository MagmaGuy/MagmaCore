package com.magmaguy.magmacore.ai;

import java.util.Optional;

public interface MindMemoryView {
    <T> Optional<T> get(MindMemoryKey<T> key);

    <T> void set(MindMemoryKey<T> key, T value);

    <T> void set(MindMemoryKey<T> key, T value, long ttlTicks);

    void forget(MindMemoryKey<?> key);

    boolean contains(MindMemoryKey<?> key);
}
