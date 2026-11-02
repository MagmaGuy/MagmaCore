package com.magmaguy.easyminecraftgoals.v26.packets;

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
            if (packets == null) return packet;
            return packets.size() == 1 ? packets.get(0) : new ClientboundBundlePacket(packets);
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            // The copy starts at the first child that changes, with the children before it.
            List<Packet<? super ClientGamePacketListener>> packets = null;
            int index = 0;
            for (Packet<? super ClientGamePacketListener> child : bundle.subPackets()) {
                if (child instanceof ClientboundSetPassengersPacket mount) {
                    List<Packet<? super ClientGamePacketListener>> composed = composeMount(viewer, mount);
                    if (composed != null && packets == null) packets = leading(bundle, index);
                    if (packets != null) {
                        if (composed != null) packets.addAll(composed);
                        else packets.add(child);
                    }
                } else {
                    Packet<?> composed = compose(viewer, child);
                    if (composed != child && packets == null) packets = leading(bundle, index);
                    if (packets != null) packets.add((Packet<? super ClientGamePacketListener>) composed);
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

    /**
     * The composed passenger list, followed by each synthetic passenger's own rotation, or null when the mount
     * goes out as it is: no synthetic passenger rides the vehicle for this viewer. A client re-seats
     * every passenger it is sent, and vehicles such as horses turn each one they take to their own view
     * (AbstractHorse.addPassenger); a fixed-billboard display pose is built on the rotation it was spawned
     * with, so that rotation is put back in the same bundle.
     */
    private static List<Packet<? super ClientGamePacketListener>> composeMount(UUID viewer, ClientboundSetPassengersPacket mount) {
        int[] passengers = PacketPassengerRegistry.compose(viewer, mount.getVehicle(), mount.getPassengers());
        List<PacketPassengerRegistry.Attachment> attachments = PacketPassengerRegistry.attachments(viewer, mount.getVehicle());
        boolean unchanged = Arrays.equals(passengers, mount.getPassengers());
        if (unchanged && attachments.isEmpty()) return null;
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>(1 + attachments.size());
        packets.add(unchanged ? mount : create(mount.getVehicle(), passengers));
        for (PacketPassengerRegistry.Attachment attachment : attachments)
            packets.add(new ClientboundMoveEntityPacket.Rot(attachment.passenger(), angle(attachment.yaw()), angle(attachment.pitch()), false));
        return packets;
    }

    private static byte angle(float degrees) {
        return (byte) Math.floor(degrees * 256.0F / 360.0F);
    }
}
