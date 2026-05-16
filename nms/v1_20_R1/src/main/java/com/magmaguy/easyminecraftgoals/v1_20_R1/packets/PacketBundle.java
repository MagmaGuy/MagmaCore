package com.magmaguy.easyminecraftgoals.v1_20_R1.packets;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.thirdparty.BedrockChecker;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.v1_20_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.*;

public class PacketBundle implements AbstractPacketBundle {
    private final List<PacketBundleEntry> entries = new ArrayList<>();
    private static final int MAX_PACKETS_PER_BUNDLE = 3000;

    public PacketBundle() {
    }

    @Override
    public void addPacket(Object packet, List<Player> viewers) {
        entries.add(new PacketBundleEntry((Packet<?>) packet, viewers));
    }

    @Override
    public void send() {
        // Group packets by player for efficiency
        Map<Player, List<Packet<ClientGamePacketListener>>> playerPackets = new HashMap<>();

        for (PacketBundleEntry entry : entries) {
            // Skip if no viewers
            if (entry.viewers().isEmpty()) continue;

            // Type check and cast once per packet
            if (!isClientGamePacket(entry.packet())) continue;

            @SuppressWarnings("unchecked")
            Packet<ClientGamePacketListener> clientPacket = (Packet<ClientGamePacketListener>) entry.packet();

            // Add to each viewer's packet list
            for (Player viewer : entry.viewers()) {
                playerPackets.computeIfAbsent(viewer, k -> new ArrayList<>()).add(clientPacket);
            }
        }

        // Send bundles to each player, splitting into chunks of 3000 if needed.
        // For Bedrock viewers, skip the bundle wrapper entirely: Geyser unwraps
        // ClientboundBundlePacket upstream of its translator dispatch and forwards
        // each inner packet individually as its own Bedrock packet anyway, so the
        // wrapper costs an allocation and a HashSet per tick for no benefit. More
        // importantly, the wrapper has been observed to interact poorly with
        // freshly-spawned entities on Bedrock: a metadata update for an entity
        // that Geyser hasn't fully registered yet can be processed out of order
        // and silently dropped, breaking bone rotation on FMM models until the
        // entity is destroyed and respawned (the "move away and back to fix it"
        // symptom). Sending un-bundled gives Geyser one packet at a time, in
        // order, on the same single Netty flush.
        playerPackets.forEach((player, packets) -> {
            if (packets.isEmpty() || player == null || !player.isOnline()) return;

            if (BedrockChecker.isBedrock(player)) {
                for (Packet<ClientGamePacketListener> p : packets) {
                    sendPacketDirect(player, p);
                }
                return;
            }

            // Split into chunks of MAX_PACKETS_PER_BUNDLE
            for (int i = 0; i < packets.size(); i += MAX_PACKETS_PER_BUNDLE) {
                int end = Math.min(i + MAX_PACKETS_PER_BUNDLE, packets.size());
                List<Packet<ClientGamePacketListener>> chunk = packets.subList(i, end);

                ClientboundBundlePacket bundle = new ClientboundBundlePacket(new HashSet<>(chunk));
                sendPacketBundle(player, bundle);
            }
        });

        int bundleCount = playerPackets.values().stream()
                .mapToInt(packets -> (packets.size() + MAX_PACKETS_PER_BUNDLE - 1) / MAX_PACKETS_PER_BUNDLE)
                .sum();

    }

    private boolean isClientGamePacket(Packet<?> packet) {
        // This is a bit hacky but works for most NMS versions
        // Alternatively, you could check specific packet types you know are valid
        return packet != null;
    }

    private void sendPacketBundle(Player player, Packet<?> nmsPacket) {
        if (nmsPacket == null) return;

        ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
        nmsPlayer.connection.send(nmsPacket);
    }

    /** Send a single packet directly to a player without bundle wrapping. */
    private void sendPacketDirect(Player player, Packet<?> nmsPacket) {
        if (nmsPacket == null) return;
        ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
        nmsPlayer.connection.send(nmsPacket);
    }

    private record PacketBundleEntry(Packet<?> packet, List<Player> viewers) {
    }

}
