package com.magmaguy.easyminecraftgoals.v1_21_R6.packets;

import com.magmaguy.easyminecraftgoals.internal.PacketPassengerRegistry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Copies passenger packets per recipient; never changes a packet shared with another player. */
public final class PassengerPackets {
    private PassengerPackets() { }

    public static ClientboundSetPassengersPacket create(int vehicle, int[] passengers) {
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
            List<Packet<? super ClientGamePacketListener>> packets = composeMount(viewer, mount);
            return packets.size() == 1 ? packets.get(0) : new ClientboundBundlePacket(packets);
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
                if (child instanceof ClientboundSetPassengersPacket mount) {
                    List<Packet<? super ClientGamePacketListener>> composed = composeMount(viewer, mount);
                    changed |= composed.size() != 1 || composed.get(0) != child;
                    packets.addAll(composed);
                    continue;
                }
                Packet<?> composed = compose(viewer, child);
                changed |= composed != child;
                packets.add((Packet<? super ClientGamePacketListener>) composed);
            }
            return changed ? new ClientboundBundlePacket(packets) : packet;
        }
        return packet;
    }

    /**
     * The composed passenger list, followed by each synthetic passenger's own rotation. A client re-seats
     * every passenger it is sent, and vehicles such as horses turn each one they take to their own view
     * (AbstractHorse.addPassenger); a fixed-billboard display pose is built on the rotation it was spawned
     * with, so that rotation is put back in the same bundle.
     */
    private static List<Packet<? super ClientGamePacketListener>> composeMount(UUID viewer, ClientboundSetPassengersPacket mount) {
        int[] passengers = PacketPassengerRegistry.compose(viewer, mount.getVehicle(), mount.getPassengers());
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        packets.add(Arrays.equals(passengers, mount.getPassengers()) ? mount : create(mount.getVehicle(), passengers));
        for (PacketPassengerRegistry.Attachment attachment : PacketPassengerRegistry.attachments(viewer, mount.getVehicle()))
            packets.add(new ClientboundMoveEntityPacket.Rot(attachment.passenger(), angle(attachment.yaw()), angle(attachment.pitch()), false));
        return packets;
    }

    private static byte angle(float degrees) {
        return (byte) Math.floor(degrees * 256.0F / 360.0F);
    }
}
