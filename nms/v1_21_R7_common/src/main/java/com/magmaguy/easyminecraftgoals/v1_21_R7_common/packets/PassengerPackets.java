package com.magmaguy.easyminecraftgoals.v1_21_R7_common.packets;

import com.magmaguy.easyminecraftgoals.internal.PacketPassengerRegistry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Copies passenger packets per recipient; never changes a packet shared with another player. */
final class PassengerPackets {
    private PassengerPackets() { }

    static ClientboundSetPassengersPacket create(int vehicle, int[] passengers) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(vehicle);
            buffer.writeVarIntArray(passengers);
            return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    @SuppressWarnings("unchecked")
    static Packet<?> compose(UUID viewer, Packet<?> packet) {
        if (!(packet instanceof ClientboundSetPassengersPacket) && !(packet instanceof ClientboundBundlePacket)) return packet;
        if (!PacketPassengerRegistry.hasAttachments(viewer)) return packet;
        if (packet instanceof ClientboundSetPassengersPacket mount) {
            int[] passengers = PacketPassengerRegistry.compose(viewer, mount.getVehicle(), mount.getPassengers());
            return Arrays.equals(passengers, mount.getPassengers()) ? packet : create(mount.getVehicle(), passengers);
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            boolean hasMount = false;
            for (Packet<?> child : bundle.subPackets()) {
                if (child instanceof ClientboundSetPassengersPacket || child instanceof ClientboundBundlePacket) {
                    hasMount = true;
                    break;
                }
            }
            if (!hasMount) return packet;
            List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
            boolean changed = false;
            for (Packet<? super ClientGamePacketListener> child : bundle.subPackets()) {
                Packet<?> composed = compose(viewer, child);
                changed |= composed != child;
                packets.add((Packet<? super ClientGamePacketListener>) composed);
            }
            return changed ? new ClientboundBundlePacket(packets) : packet;
        }
        return packet;
    }
}
