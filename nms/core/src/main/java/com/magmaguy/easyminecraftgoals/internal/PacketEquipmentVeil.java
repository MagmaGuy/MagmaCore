package com.magmaguy.easyminecraftgoals.internal;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-viewer entity ids whose equipment reaches that viewer emptied. An invisible mob still renders its armor
 * and held items, so a model that keeps its hidden entity tracked by the viewers of the model veils it here
 * and the outbound packet hook replaces every equipment slot it sends them with air. Bedrock viewers are never
 * veiled by the callers that show them the vanilla mob instead.
 */
public final class PacketEquipmentVeil {
    // Each viewer's veiled ids, sorted. A change replaces the array, so the packet hook reads one without a lock
    // or a boxed lookup for every packet it checks.
    private static final Map<UUID, int[]> VEILS = new ConcurrentHashMap<>();

    private PacketEquipmentVeil() { }

    public static void veil(UUID viewer, int entityId) {
        VEILS.compute(viewer, (ignored, ids) -> {
            if (ids == null) return new int[]{entityId};
            int index = Arrays.binarySearch(ids, entityId);
            if (index >= 0) return ids;
            int insertion = -index - 1;
            int[] grown = new int[ids.length + 1];
            System.arraycopy(ids, 0, grown, 0, insertion);
            grown[insertion] = entityId;
            System.arraycopy(ids, insertion, grown, insertion + 1, ids.length - insertion);
            return grown;
        });
    }

    public static void unveil(UUID viewer, int entityId) {
        VEILS.computeIfPresent(viewer, (ignored, ids) -> {
            int index = Arrays.binarySearch(ids, entityId);
            if (index < 0) return ids;
            if (ids.length == 1) return null;
            int[] shrunk = new int[ids.length - 1];
            System.arraycopy(ids, 0, shrunk, 0, index);
            System.arraycopy(ids, index + 1, shrunk, index, ids.length - index - 1);
            return shrunk;
        });
    }

    /** Cheap gate for the packet hook: most viewers veil nothing. */
    public static boolean hasVeils(UUID viewer) {
        return VEILS.containsKey(viewer);
    }

    public static boolean isVeiled(UUID viewer, int entityId) {
        return isVeiled(veils(viewer), entityId);
    }

    /**
     * The viewer's veiled entity ids, sorted, or null when it veils none. The array is never changed once returned,
     * so a packet hook can check every packet of a bundle against one read; do not change it either.
     */
    public static int[] veils(UUID viewer) {
        return VEILS.get(viewer);
    }

    /** Whether {@code entityId} is in ids returned by {@link #veils(UUID)}. */
    public static boolean isVeiled(int[] veils, int entityId) {
        return veils != null && Arrays.binarySearch(veils, entityId) >= 0;
    }

    public static void clearViewer(UUID viewer) {
        VEILS.remove(viewer);
    }

    public static void clear() {
        VEILS.clear();
    }
}
