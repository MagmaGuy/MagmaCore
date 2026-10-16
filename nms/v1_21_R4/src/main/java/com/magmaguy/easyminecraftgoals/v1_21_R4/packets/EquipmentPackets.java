package com.magmaguy.easyminecraftgoals.v1_21_R4.packets;

import com.magmaguy.easyminecraftgoals.internal.PacketEquipmentVeil;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Empties the equipment of veiled entities per recipient; never changes a packet shared with another player. */
public final class EquipmentPackets {
    private EquipmentPackets() { }

    @SuppressWarnings("unchecked")
    static Packet<?> veil(UUID viewer, Packet<?> packet) {
        if (!(packet instanceof ClientboundSetEquipmentPacket) && !(packet instanceof ClientboundBundlePacket)) return packet;
        if (!PacketEquipmentVeil.hasVeils(viewer)) return packet;
        if (packet instanceof ClientboundSetEquipmentPacket equipment) {
            if (!PacketEquipmentVeil.isVeiled(viewer, equipment.getEntity())) return packet;
            List<Pair<EquipmentSlot, ItemStack>> emptied = new ArrayList<>(equipment.getSlots().size());
            for (Pair<EquipmentSlot, ItemStack> slot : equipment.getSlots()) emptied.add(Pair.of(slot.getFirst(), ItemStack.EMPTY));
            return new ClientboundSetEquipmentPacket(equipment.getEntity(), emptied);
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            boolean hasEquipment = false;
            for (Packet<?> child : bundle.subPackets()) {
                if (child instanceof ClientboundSetEquipmentPacket || child instanceof ClientboundBundlePacket) {
                    hasEquipment = true;
                    break;
                }
            }
            if (!hasEquipment) return packet;
            List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
            boolean changed = false;
            for (Packet<? super ClientGamePacketListener> child : bundle.subPackets()) {
                Packet<?> veiled = veil(viewer, child);
                changed |= veiled != child;
                packets.add((Packet<? super ClientGamePacketListener>) veiled);
            }
            return changed ? new ClientboundBundlePacket(packets) : packet;
        }
        return packet;
    }
}
