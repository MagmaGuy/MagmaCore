package com.magmaguy.easyminecraftgoals.v1_21_R6.packets;

import com.magmaguy.easyminecraftgoals.internal.PacketHeadHat;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shows the hat of {@link PacketHeadHat} on the head of every hatted entity, per recipient. An empty head slot in
 * the equipment the server sends arrives holding the hat, and a real helmet arrives unchanged. A spawn is followed by
 * the hat on its own, because an entity whose equipment is all empty spawns with no equipment entry; when it does
 * wear something, the server's own equipment entry comes after the spawn and keeps any real helmet on top. Never
 * changes a packet shared with another player, and copies nothing until a packet needs the hat.
 */
public final class HeadHatPackets {
    private HeadHatPackets() { }

    static Packet<?> apply(UUID viewer, Packet<?> packet) {
        if (!(packet instanceof ClientboundSetEquipmentPacket) && !(packet instanceof ClientboundAddEntityPacket)
                && !(packet instanceof ClientboundBundlePacket)) return packet;
        if (!(PacketHeadHat.hat() instanceof ItemStack hat)) return packet;
        int[] hatted = PacketHeadHat.hatted();
        if (hatted.length == 0 || PacketHeadHat.skips(viewer)) return packet;
        return apply(hatted, hat, packet);
    }

    @SuppressWarnings("unchecked")
    private static Packet<?> apply(int[] hatted, ItemStack hat, Packet<?> packet) {
        if (packet instanceof ClientboundAddEntityPacket add) {
            // Outside a bundle the spawn and its hat travel as a bundle of two.
            return PacketHeadHat.wears(hatted, add.getId())
                    ? new ClientboundBundlePacket(List.of((Packet<? super ClientGamePacketListener>) packet, worn(add.getId(), hat)))
                    : packet;
        }
        if (packet instanceof ClientboundSetEquipmentPacket equipment) {
            if (!PacketHeadHat.wears(hatted, equipment.getEntity())) return packet;
            List<Pair<EquipmentSlot, ItemStack>> slots = equipment.getSlots();
            for (int i = 0; i < slots.size(); i++) {
                Pair<EquipmentSlot, ItemStack> slot = slots.get(i);
                if (slot.getFirst() != EquipmentSlot.HEAD || !slot.getSecond().isEmpty()) continue;
                List<Pair<EquipmentSlot, ItemStack>> replaced = new ArrayList<>(slots);
                replaced.set(i, Pair.of(EquipmentSlot.HEAD, hat.copy()));
                return new ClientboundSetEquipmentPacket(equipment.getEntity(), replaced);
            }
            return packet;
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            // The copy starts at the first child that changes, with the children before it.
            List<Packet<? super ClientGamePacketListener>> packets = null;
            int index = 0;
            for (Packet<? super ClientGamePacketListener> child : bundle.subPackets()) {
                if (child instanceof ClientboundAddEntityPacket add && PacketHeadHat.wears(hatted, add.getId())) {
                    if (packets == null) packets = leading(bundle, index);
                    packets.add(child);
                    packets.add(worn(add.getId(), hat));
                } else {
                    Packet<?> rewritten = apply(hatted, hat, child);
                    if (rewritten != child && packets == null) packets = leading(bundle, index);
                    if (packets != null) packets.add((Packet<? super ClientGamePacketListener>) rewritten);
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

    private static ClientboundSetEquipmentPacket worn(int entityId, ItemStack hat) {
        return new ClientboundSetEquipmentPacket(entityId, List.of(Pair.of(EquipmentSlot.HEAD, hat.copy())));
    }
}
