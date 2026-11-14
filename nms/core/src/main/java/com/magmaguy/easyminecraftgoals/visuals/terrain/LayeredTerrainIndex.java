package com.magmaguy.easyminecraftgoals.visuals.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Pure ownership index for packet-only terrain visuals that overlap at the same source block.
 * New impacts win without destroying older layers, so removing the winner can reveal the still
 * active visual below it without briefly restoring the real block.
 */
final class LayeredTerrainIndex<K, V> {
    private final Map<UUID, Layer<K, V>> layers = new LinkedHashMap<>();
    /** Every layer's value at each key, by layer priority; the highest priority is the visible one. */
    private final Map<K, TreeMap<Long, V>> valuesByKey = new HashMap<>();
    private long nextPriority;

    List<VisibilityChange<K, V>> replace(UUID impactId, Map<K, V> values) {
        Objects.requireNonNull(impactId, "impactId");
        Objects.requireNonNull(values, "values");
        LinkedHashMap<K, V> replacement = new LinkedHashMap<>();
        values.forEach((key, value) -> replacement.put(
                Objects.requireNonNull(key, "terrain coordinate"),
                Objects.requireNonNull(value, "terrain visual")));
        if (replacement.isEmpty()) return remove(impactId);

        Layer<K, V> previous = layers.get(impactId);
        Set<K> affected = new LinkedHashSet<>();
        if (previous != null) affected.addAll(previous.values().keySet());
        affected.addAll(replacement.keySet());
        Map<K, Optional<V>> before = snapshot(affected);

        long priority = previous == null ? ++nextPriority : previous.priority();
        if (previous != null) unindex(previous);
        Layer<K, V> layer = new Layer<>(priority, Map.copyOf(replacement));
        layers.put(impactId, layer);
        index(layer);
        return changes(affected, before);
    }

    List<VisibilityChange<K, V>> remove(UUID impactId) {
        Objects.requireNonNull(impactId, "impactId");
        Layer<K, V> existing = layers.get(impactId);
        if (existing == null) return List.of();

        Set<K> affected = new LinkedHashSet<>(existing.values().keySet());
        Map<K, Optional<V>> before = snapshot(affected);
        layers.remove(impactId);
        unindex(existing);
        return changes(affected, before);
    }

    Optional<V> visible(K key) {
        Objects.requireNonNull(key, "key");
        TreeMap<Long, V> values = valuesByKey.get(key);
        return values == null ? Optional.empty() : Optional.of(values.lastEntry().getValue());
    }

    private void index(Layer<K, V> layer) {
        layer.values().forEach((key, value) ->
                valuesByKey.computeIfAbsent(key, ignored -> new TreeMap<>()).put(layer.priority(), value));
    }

    private void unindex(Layer<K, V> layer) {
        for (K key : layer.values().keySet()) {
            TreeMap<Long, V> values = valuesByKey.get(key);
            if (values == null) continue;
            values.remove(layer.priority());
            if (values.isEmpty()) valuesByKey.remove(key);
        }
    }

    boolean isEmpty() {
        return layers.isEmpty();
    }

    private Map<K, Optional<V>> snapshot(Set<K> keys) {
        Map<K, Optional<V>> snapshot = new LinkedHashMap<>();
        for (K key : keys) snapshot.put(key, visible(key));
        return snapshot;
    }

    private List<VisibilityChange<K, V>> changes(
            Set<K> affected,
            Map<K, Optional<V>> before) {
        List<VisibilityChange<K, V>> changes = new ArrayList<>();
        for (K key : affected) {
            Optional<V> after = visible(key);
            Optional<V> prior = before.getOrDefault(key, Optional.empty());
            if (!prior.equals(after)) changes.add(new VisibilityChange<>(key, prior, after));
        }
        return List.copyOf(changes);
    }

    record VisibilityChange<K, V>(K key, Optional<V> before, Optional<V> after) {
        VisibilityChange {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
        }
    }

    private record Layer<K, V>(long priority, Map<K, V> values) {
    }
}
