package com.magmaguy.easyminecraftgoals.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-observer synthetic passengers, composed with each authoritative passenger snapshot. */
public final class PacketPassengerRegistry {
    /** A synthetic passenger and the rotation its viewer must keep showing it with. */
    public record Attachment(int passenger, float yaw, float pitch) { }

    private static final Map<UUID, Map<Integer, Map<Integer, Attachment>>> ATTACHMENTS = new ConcurrentHashMap<>();

    private PacketPassengerRegistry() { }

    /**
     * Vehicles may turn a passenger they take to their own view (horses do, on the client as well), so the
     * rotation the passenger was spawned with is kept here and restored after every passenger packet.
     */
    public static synchronized void attach(UUID viewer, int vehicle, int passenger, float yaw, float pitch) {
        ATTACHMENTS.computeIfAbsent(viewer, ignored -> new HashMap<>())
                .computeIfAbsent(vehicle, ignored -> new LinkedHashMap<>()).put(passenger, new Attachment(passenger, yaw, pitch));
    }

    public static synchronized void detach(UUID viewer, int vehicle, int passenger) {
        Map<Integer, Map<Integer, Attachment>> vehicles = ATTACHMENTS.get(viewer);
        if (vehicles == null) return;
        Map<Integer, Attachment> passengers = vehicles.get(vehicle);
        if (passengers == null) return;
        passengers.remove(passenger);
        if (passengers.isEmpty()) vehicles.remove(vehicle);
        if (vehicles.isEmpty()) ATTACHMENTS.remove(viewer);
    }

    /** Preserve native ordering (including the controlling passenger) and do not mutate shared packets. */
    public static synchronized int[] compose(UUID viewer, int vehicle, int[] nativePassengers) {
        Map<Integer, Map<Integer, Attachment>> vehicles = ATTACHMENTS.get(viewer);
        Map<Integer, Attachment> synthetic = vehicles == null ? null : vehicles.get(vehicle);
        if (synthetic == null || synthetic.isEmpty()) return nativePassengers;
        Map<Integer, Boolean> merged = new LinkedHashMap<>();
        for (int passenger : nativePassengers) merged.put(passenger, Boolean.TRUE);
        for (int passenger : synthetic.keySet()) merged.put(passenger, Boolean.TRUE);
        return merged.keySet().stream().mapToInt(Integer::intValue).toArray();
    }

    /** The viewer's synthetic passengers of the vehicle, in attachment order. */
    public static synchronized List<Attachment> attachments(UUID viewer, int vehicle) {
        Map<Integer, Map<Integer, Attachment>> vehicles = ATTACHMENTS.get(viewer);
        Map<Integer, Attachment> synthetic = vehicles == null ? null : vehicles.get(vehicle);
        return synthetic == null ? List.of() : new ArrayList<>(synthetic.values());
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
