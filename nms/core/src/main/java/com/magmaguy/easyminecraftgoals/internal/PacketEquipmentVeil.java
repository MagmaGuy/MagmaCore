package com.magmaguy.easyminecraftgoals.internal;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-viewer entity ids whose equipment reaches that viewer emptied. An invisible mob still renders its armor
 * and held items, so a model that keeps its hidden entity tracked by the viewers of the model veils it here
 * and the outbound packet hook replaces every equipment slot it sends them with air. Bedrock viewers are never
 * veiled by the callers that show them the vanilla mob instead.
 */
public final class PacketEquipmentVeil {
    private static final Map<UUID, Set<Integer>> VEILS = new ConcurrentHashMap<>();

    private PacketEquipmentVeil() { }

    public static void veil(UUID viewer, int entityId) {
        VEILS.compute(viewer, (ignored, ids) -> {
            Set<Integer> set = ids == null ? ConcurrentHashMap.newKeySet() : ids;
            set.add(entityId);
            return set;
        });
    }

    public static void unveil(UUID viewer, int entityId) {
        VEILS.computeIfPresent(viewer, (ignored, ids) -> {
            ids.remove(entityId);
            return ids.isEmpty() ? null : ids;
        });
    }

    /** Cheap gate for the packet hook: most viewers veil nothing. */
    public static boolean hasVeils(UUID viewer) {
        return VEILS.containsKey(viewer);
    }

    public static boolean isVeiled(UUID viewer, int entityId) {
        Set<Integer> ids = VEILS.get(viewer);
        return ids != null && ids.contains(entityId);
    }

    public static void clearViewer(UUID viewer) {
        VEILS.remove(viewer);
    }

    public static void clear() {
        VEILS.clear();
    }
}
