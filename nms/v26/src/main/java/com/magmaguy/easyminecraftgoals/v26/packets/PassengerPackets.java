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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            List<Packet<? super ClientGamePacketListener>> packets = composeMount(viewer, mount, null, true);
            if (packets == null) return packet;
            return packets.size() == 1 ? packets.get(0) : new ClientboundBundlePacket(packets);
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            Map<Integer, Integer> lastMounts = repeatedMounts(bundle);
            // The rotations each repeated vehicle's earlier lists owe, put back after its last list.
            Map<Integer, Map<Integer, PacketPassengerRegistry.Attachment>> owed = null;
            // The copy starts at the first child that changes, with the children before it.
            List<Packet<? super ClientGamePacketListener>> packets = null;
            int index = 0;
            for (Packet<? super ClientGamePacketListener> child : bundle.subPackets()) {
                if (child instanceof ClientboundSetPassengersPacket mount) {
                    Integer last = lastMounts == null ? null : lastMounts.get(mount.getVehicle());
                    List<Packet<? super ClientGamePacketListener>> composed;
                    if (last != null && last != index) {
                        if (owed == null) owed = new HashMap<>();
                        composed = composeMount(viewer, mount,
                                owed.computeIfAbsent(mount.getVehicle(), ignored -> new LinkedHashMap<>()), false);
                    } else {
                        composed = composeMount(viewer, mount, owed == null ? null : owed.remove(mount.getVehicle()), true);
                    }
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
     * Each vehicle whose passengers the bundle sets more than once, with the index of its last list; null when
     * none repeats. A model's admission sends one list per bone, all for the same vehicle.
     */
    private static Map<Integer, Integer> repeatedMounts(ClientboundBundlePacket bundle) {
        int mounts = 0;
        for (Packet<? super ClientGamePacketListener> child : bundle.subPackets())
            if (child instanceof ClientboundSetPassengersPacket) mounts++;
        if (mounts < 2) return null;
        Map<Integer, Integer> lastMounts = new HashMap<>();
        boolean repeated = false;
        int index = 0;
        for (Packet<? super ClientGamePacketListener> child : bundle.subPackets()) {
            if (child instanceof ClientboundSetPassengersPacket mount && lastMounts.put(mount.getVehicle(), index) != null)
                repeated = true;
            index++;
        }
        return repeated ? lastMounts : null;
    }

    /**
     * The composed passenger list, followed by each synthetic passenger's own rotation, or null when the mount
     * goes out as it is. A client re-seats every passenger it is sent, and vehicles such as horses turn each one
     * they take to their own view (AbstractHorse.addPassenger); a fixed-billboard display pose is built on the
     * rotation it was spawned with, so that rotation is put back in the same bundle.
     *
     * <p>A client handles a bundle in one go, and each list re-seats every passenger again, so only the
     * vehicle's last list in a bundle needs the rotations. An earlier list ({@code rotate} false) records them
     * in {@code owed} instead; the last list puts back its own and every owed one it does not cover.
     */
    private static List<Packet<? super ClientGamePacketListener>> composeMount(
            UUID viewer, ClientboundSetPassengersPacket mount,
            Map<Integer, PacketPassengerRegistry.Attachment> owed, boolean rotate) {
        int[] passengers = PacketPassengerRegistry.compose(viewer, mount.getVehicle(), mount.getPassengers());
        List<PacketPassengerRegistry.Attachment> attachments = PacketPassengerRegistry.attachments(viewer, mount.getVehicle());
        boolean unchanged = Arrays.equals(passengers, mount.getPassengers());
        if (!rotate) {
            for (PacketPassengerRegistry.Attachment attachment : attachments) owed.put(attachment.passenger(), attachment);
            return unchanged ? null : List.of(create(mount.getVehicle(), passengers));
        }
        if (owed != null) for (PacketPassengerRegistry.Attachment attachment : attachments) owed.remove(attachment.passenger());
        int owedCount = owed == null ? 0 : owed.size();
        if (unchanged && attachments.isEmpty() && owedCount == 0) return null;
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>(1 + attachments.size() + owedCount);
        packets.add(unchanged ? mount : create(mount.getVehicle(), passengers));
        for (PacketPassengerRegistry.Attachment attachment : attachments) packets.add(rotation(attachment));
        if (owedCount > 0) for (PacketPassengerRegistry.Attachment attachment : owed.values()) packets.add(rotation(attachment));
        return packets;
    }

    private static ClientboundMoveEntityPacket.Rot rotation(PacketPassengerRegistry.Attachment attachment) {
        return new ClientboundMoveEntityPacket.Rot(attachment.passenger(), angle(attachment.yaw()), angle(attachment.pitch()), false);
    }

    private static byte angle(float degrees) {
        return (byte) Math.floor(degrees * 256.0F / 360.0F);
    }
}
