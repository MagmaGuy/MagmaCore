package com.magmaguy.easyminecraftgoals.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketEquipmentVeilTest {
    private final UUID viewer = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @AfterEach
    void clear() {
        PacketEquipmentVeil.clear();
    }

    @Test
    void veilsArePerViewer() {
        PacketEquipmentVeil.veil(viewer, 700);
        assertTrue(PacketEquipmentVeil.isVeiled(viewer, 700));
        assertFalse(PacketEquipmentVeil.isVeiled(viewer, 701));
        assertFalse(PacketEquipmentVeil.isVeiled(other, 700));
        assertTrue(PacketEquipmentVeil.hasVeils(viewer));
        assertFalse(PacketEquipmentVeil.hasVeils(other));
    }

    @Test
    void idsStaySortedAndUnique() {
        for (int id : new int[]{900, 12, 5000, 12, 300}) PacketEquipmentVeil.veil(viewer, id);
        assertArrayEquals(new int[]{12, 300, 900, 5000}, PacketEquipmentVeil.veils(viewer));
        PacketEquipmentVeil.unveil(viewer, 300);
        PacketEquipmentVeil.unveil(viewer, 301);
        assertArrayEquals(new int[]{12, 900, 5000}, PacketEquipmentVeil.veils(viewer));
    }

    @Test
    void theLastUnveilForgetsTheViewer() {
        PacketEquipmentVeil.veil(viewer, 42);
        PacketEquipmentVeil.unveil(viewer, 42);
        assertFalse(PacketEquipmentVeil.hasVeils(viewer));
        assertNull(PacketEquipmentVeil.veils(viewer));
        assertFalse(PacketEquipmentVeil.isVeiled(viewer, 42));
    }

    @Test
    void aReadArrayNeverChanges() {
        PacketEquipmentVeil.veil(viewer, 1);
        int[] read = PacketEquipmentVeil.veils(viewer);
        PacketEquipmentVeil.veil(viewer, 2);
        assertArrayEquals(new int[]{1}, read);
        assertNotSame(read, PacketEquipmentVeil.veils(viewer));
        assertTrue(PacketEquipmentVeil.isVeiled(read, 1));
        assertFalse(PacketEquipmentVeil.isVeiled(read, 2));
        assertFalse(PacketEquipmentVeil.isVeiled((int[]) null, 1));
    }

    @Test
    void clearViewerDropsOnlyThatViewer() {
        PacketEquipmentVeil.veil(viewer, 1);
        PacketEquipmentVeil.veil(other, 1);
        PacketEquipmentVeil.clearViewer(viewer);
        assertFalse(PacketEquipmentVeil.hasVeils(viewer));
        assertTrue(PacketEquipmentVeil.isVeiled(other, 1));
    }
}
