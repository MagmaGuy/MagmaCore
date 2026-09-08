package com.magmaguy.easyminecraftgoals.internal;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-observer synthetic passengers, composed with each authoritative passenger snapshot. */
public final class PacketPassengerRegistry {
    private static final Map<UUID, Map<Integer, Set<Integer>>> ATTACHMENTS = new ConcurrentHashMap<>();

    private PacketPassengerRegistry() { }

    public static synchronized void attach(UUID viewer, int vehicle, int passenger) {
        ATTACHMENTS.computeIfAbsent(viewer, ignored -> new HashMap<>())
                .computeIfAbsent(vehicle, ignored -> new LinkedHashSet<>()).add(passenger);
    }

    public static synchronized void detach(UUID viewer, int vehicle, int passenger) {
        Map<Integer, Set<Integer>> vehicles = ATTACHMENTS.get(viewer);
        if (vehicles == null) return;
        Set<Integer> passengers = vehicles.get(vehicle);
        if (passengers == null) return;
        passengers.remove(passenger);
        if (passengers.isEmpty()) vehicles.remove(vehicle);
        if (vehicles.isEmpty()) ATTACHMENTS.remove(viewer);
    }

    /** Preserve native ordering (including the controlling passenger) and do not mutate shared packets. */
    public static synchronized int[] compose(UUID viewer, int vehicle, int[] nativePassengers) {
        Map<Integer, Set<Integer>> vehicles = ATTACHMENTS.get(viewer);
        Set<Integer> synthetic = vehicles == null ? null : vehicles.get(vehicle);
        if (synthetic == null || synthetic.isEmpty()) return nativePassengers;
        Set<Integer> merged = new LinkedHashSet<>();
        for (int passenger : nativePassengers) merged.add(passenger);
        merged.addAll(synthetic);
        return merged.stream().mapToInt(Integer::intValue).toArray();
    }

    public static synchronized void clearViewer(UUID viewer) {
        ATTACHMENTS.remove(viewer);
    }

    public static boolean hasAttachments(UUID viewer) {
        return ATTACHMENTS.containsKey(viewer);
    }

    public static synchronized void clear() {
        ATTACHMENTS.clear();
    }
}
