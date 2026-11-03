package com.magmaguy.easyminecraftgoals.v26.packets;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.v26.CraftBukkitBridge;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.entity.Player;

import java.util.*;

public class PacketBundle implements AbstractPacketBundle {
    private final List<PacketBundleEntry> entries = new ArrayList<>();
    private static final int MAX_PACKETS_PER_BUNDLE = 3000;

    public PacketBundle() {
    }

    @Override
    public void addPacket(Object packet, List<Player> viewers) {
        entries.add(new PacketBundleEntry((Packet<?>) packet, viewers, false));
    }

    @Override
    public void addPosePacket(Object packet, List<Player> viewers) {
        entries.add(new PacketBundleEntry((Packet<?>) packet, viewers, true));
    }

    @Override
    public void send() {
        // Group packets by player for efficiency
        Map<Player, List<Packet<ClientGamePacketListener>>> playerPackets = new HashMap<>();
        // The players whose packets include poses; their bundle reports when it has left the server.
        java.util.Set<Player> posed = new java.util.HashSet<>();

        for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
            PacketBundleEntry entry = entries.get(entryIndex);
            // Skip if no viewers
            if (entry.viewers().isEmpty()) continue;

            // Type check and cast once per packet
            if (!isClientGamePacket(entry.packet())) continue;

            @SuppressWarnings("unchecked")
            Packet<ClientGamePacketListener> clientPacket = (Packet<ClientGamePacketListener>) entry.packet();

            // Add to each viewer's packet list
            List<Player> viewers = entry.viewers();
            if (viewers instanceof RandomAccess) {
                for (int viewerIndex = 0; viewerIndex < viewers.size(); viewerIndex++) {
                    playerPackets.computeIfAbsent(viewers.get(viewerIndex), k -> new ArrayList<>()).add(clientPacket);
                }
            } else {
                for (Player viewer : viewers) {
                    playerPackets.computeIfAbsent(viewer, k -> new ArrayList<>()).add(clientPacket);
                }
            }
            if (entry.pose()) posed.addAll(viewers);
        }

        // Send bundles to each player, splitting into chunks of 3000 if needed.
        playerPackets.forEach((player, packets) -> {
            if (packets.isEmpty() || player == null || !player.isOnline()) return;

            // Split into chunks of MAX_PACKETS_PER_BUNDLE
            for (int i = 0; i < packets.size(); i += MAX_PACKETS_PER_BUNDLE) {
                int end = Math.min(i + MAX_PACKETS_PER_BUNDLE, packets.size());
                List<Packet<ClientGamePacketListener>> chunk = packets.subList(i, end);

                ClientboundBundlePacket bundle = new ClientboundBundlePacket(new ArrayList<>(chunk));
                sendPacketBundle(player, bundle, end == packets.size() && posed.contains(player));
            }
        });

    }

    private boolean isClientGamePacket(Packet<?> packet) {
        // This is a bit hacky but works for most NMS versions
        // Alternatively, you could check specific packet types you know are valid
        return packet != null;
    }

    /**
     * Sends one bundle. The last bundle of a tick that carries poses tells
     * {@link com.magmaguy.easyminecraftgoals.internal.PoseBacklog} when the connection has written it.
     */
    private void sendPacketBundle(Player player, Packet<?> nmsPacket, boolean poses) {
        if (nmsPacket == null) return;

        ServerPlayer nmsPlayer = CraftBukkitBridge.getServerPlayer(player);
        if (!poses) {
            nmsPlayer.connection.send(nmsPacket);
            return;
        }
        Runnable left = com.magmaguy.easyminecraftgoals.internal.PoseBacklog.leaving(player.getUniqueId());
        try {
            nmsPlayer.connection.send(nmsPacket, future -> left.run());
        } catch (RuntimeException failure) {
            left.run();
            throw failure;
        }
    }

    private record PacketBundleEntry(Packet<?> packet, List<Player> viewers, boolean pose) {
    }

}
