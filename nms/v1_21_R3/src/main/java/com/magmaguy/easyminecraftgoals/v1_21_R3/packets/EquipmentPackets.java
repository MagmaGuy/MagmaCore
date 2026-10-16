package com.magmaguy.easyminecraftgoals.v1_21_R3.packets;

import com.magmaguy.easyminecraftgoals.internal.PacketEquipmentVeil;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Veils an entity per recipient: its equipment arrives empty and its shared flags arrive with the invisible bit
 * set, so a viewer who tracks a model's body sees nothing of the mob under the model. Never changes a packet
 * shared with another player.
 */
public final class EquipmentPackets {
    private static final int SHARED_FLAGS = 0;
    private static final byte INVISIBLE = 0x20;

    private EquipmentPackets() { }

    @SuppressWarnings("unchecked")
    static Packet<?> veil(UUID viewer, Packet<?> packet) {
        if (!(packet instanceof ClientboundSetEquipmentPacket) && !(packet instanceof ClientboundSetEntityDataPacket)
                && !(packet instanceof ClientboundBundlePacket)) return packet;
        if (!PacketEquipmentVeil.hasVeils(viewer)) return packet;
        if (packet instanceof ClientboundSetEquipmentPacket equipment) {
            if (!PacketEquipmentVeil.isVeiled(viewer, equipment.getEntity())) return packet;
            List<Pair<EquipmentSlot, ItemStack>> emptied = new ArrayList<>(equipment.getSlots().size());
            for (Pair<EquipmentSlot, ItemStack> slot : equipment.getSlots()) emptied.add(Pair.of(slot.getFirst(), ItemStack.EMPTY));
            return new ClientboundSetEquipmentPacket(equipment.getEntity(), emptied);
        }
        if (packet instanceof ClientboundSetEntityDataPacket data) {
            if (!PacketEquipmentVeil.isVeiled(viewer, data.id())) return packet;
            List<SynchedEntityData.DataValue<?>> values = data.packedItems();
            List<SynchedEntityData.DataValue<?>> replaced = null;
            for (int i = 0; i < values.size(); i++) {
                SynchedEntityData.DataValue<?> value = values.get(i);
                if (value.id() != SHARED_FLAGS || !(value.value() instanceof Byte flags) || (flags & INVISIBLE) != 0) continue;
                if (replaced == null) replaced = new ArrayList<>(values);
                replaced.set(i, new SynchedEntityData.DataValue<>(SHARED_FLAGS, EntityDataSerializers.BYTE, (byte) (flags | INVISIBLE)));
            }
            return replaced == null ? packet : new ClientboundSetEntityDataPacket(data.id(), replaced);
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            boolean hasVeiled = false;
            for (Packet<?> child : bundle.subPackets()) {
                if (child instanceof ClientboundSetEquipmentPacket || child instanceof ClientboundSetEntityDataPacket
                        || child instanceof ClientboundBundlePacket) {
                    hasVeiled = true;
                    break;
                }
            }
            if (!hasVeiled) return packet;
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
