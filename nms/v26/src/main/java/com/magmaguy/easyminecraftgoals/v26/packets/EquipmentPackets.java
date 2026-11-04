package com.magmaguy.easyminecraftgoals.v26.packets;

import com.magmaguy.easyminecraftgoals.internal.PacketEquipmentVeil;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Veils an entity per recipient: its equipment arrives empty and its shared flags arrive with the invisible bit
 * set and the glowing bit cleared, since a client outlines an invisible mob that glows, so a viewer who tracks a
 * model's body sees nothing of the mob under the model. A worn saddle arrives as a saddle with nothing to draw
 * instead: a client hands a rider control of a horse, pig or strider only while it sees a saddle on it, and the rider
 * of a model's mount is one of its viewers. A spawn whose flags are all
 * default sends no flags at all, so every veiled spawn is followed by an invisible-flags entry of its own. Never
 * changes a packet shared with another player, and copies nothing until a packet needs veiling: every packet the
 * server sends a viewer with veils passes through here.
 */
public final class EquipmentPackets {
    private static final int SHARED_FLAGS = 0;
    private static final byte INVISIBLE = 0x20;
    private static final byte GLOWING = 0x40;

    private EquipmentPackets() { }

    static Packet<?> veil(UUID viewer, Packet<?> packet) {
        if (!(packet instanceof ClientboundSetEquipmentPacket) && !(packet instanceof ClientboundSetEntityDataPacket)
                && !(packet instanceof ClientboundAddEntityPacket) && !(packet instanceof ClientboundBundlePacket)) return packet;
        int[] veils = PacketEquipmentVeil.veils(viewer);
        if (veils == null) return packet;
        return veil(veils, packet);
    }

    @SuppressWarnings("unchecked")
    private static Packet<?> veil(int[] veils, Packet<?> packet) {
        if (packet instanceof ClientboundAddEntityPacket add) {
            // Outside a bundle the spawn and its invisible flags travel as a bundle of two.
            return PacketEquipmentVeil.isVeiled(veils, add.getId())
                    ? new ClientboundBundlePacket(List.of((Packet<? super ClientGamePacketListener>) packet, invisibleFlags(add.getId())))
                    : packet;
        }
        if (packet instanceof ClientboundSetEquipmentPacket equipment) {
            if (!PacketEquipmentVeil.isVeiled(veils, equipment.getEntity())) return packet;
            List<Pair<EquipmentSlot, ItemStack>> emptied = new ArrayList<>(equipment.getSlots().size());
            for (Pair<EquipmentSlot, ItemStack> slot : equipment.getSlots())
                emptied.add(Pair.of(slot.getFirst(), slot.getFirst() == EquipmentSlot.SADDLE && !slot.getSecond().isEmpty()
                        ? unseenSaddle() : ItemStack.EMPTY));
            return new ClientboundSetEquipmentPacket(equipment.getEntity(), emptied);
        }
        if (packet instanceof ClientboundSetEntityDataPacket data) {
            if (!PacketEquipmentVeil.isVeiled(veils, data.id())) return packet;
            List<SynchedEntityData.DataValue<?>> values = data.packedItems();
            List<SynchedEntityData.DataValue<?>> replaced = null;
            for (int i = 0; i < values.size(); i++) {
                SynchedEntityData.DataValue<?> value = values.get(i);
                if (value.id() != SHARED_FLAGS || !(value.value() instanceof Byte flags)) continue;
                byte veiled = (byte) ((flags | INVISIBLE) & ~GLOWING);
                if (veiled == flags) continue;
                if (replaced == null) replaced = new ArrayList<>(values);
                replaced.set(i, new SynchedEntityData.DataValue<>(SHARED_FLAGS, EntityDataSerializers.BYTE, veiled));
            }
            return replaced == null ? packet : new ClientboundSetEntityDataPacket(data.id(), replaced);
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            // The copy starts at the first child that changes, with the children before it.
            List<Packet<? super ClientGamePacketListener>> packets = null;
            int index = 0;
            for (Packet<? super ClientGamePacketListener> child : bundle.subPackets()) {
                if (child instanceof ClientboundAddEntityPacket add && PacketEquipmentVeil.isVeiled(veils, add.getId())) {
                    if (packets == null) packets = leading(bundle, index);
                    packets.add(child);
                    packets.add(invisibleFlags(add.getId()));
                } else {
                    Packet<?> veiled = veil(veils, child);
                    if (veiled != child && packets == null) packets = leading(bundle, index);
                    if (packets != null) packets.add((Packet<? super ClientGamePacketListener>) veiled);
                }
                index++;
            }
            return packets == null ? packet : new ClientboundBundlePacket(packets);
        }
        return packet;
    }

    /** A new list holding the bundle's first {@code count} children. */
    private static List<Packet<? super ClientGamePacketListener>> leading(ClientboundBundlePacket bundle, int count) {
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        for (Packet<? super ClientGamePacketListener> child : bundle.subPackets()) {
            if (packets.size() == count) break;
            packets.add(child);
        }
        return packets;
    }

    /** A saddle that equips the saddle slot with no model, so the client sees a saddled mob and draws nothing. */
    private static ItemStack unseenSaddle() {
        ItemStack saddle = new ItemStack(Items.SADDLE);
        saddle.set(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.SADDLE).build());
        return saddle;
    }

    private static ClientboundSetEntityDataPacket invisibleFlags(int entityId) {
        return new ClientboundSetEntityDataPacket(entityId,
                List.of(new SynchedEntityData.DataValue<>(SHARED_FLAGS, EntityDataSerializers.BYTE, INVISIBLE)));
    }
}
