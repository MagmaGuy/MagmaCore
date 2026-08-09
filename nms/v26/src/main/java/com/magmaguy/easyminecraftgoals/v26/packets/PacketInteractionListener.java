package com.magmaguy.easyminecraftgoals.v26.packets;

import com.magmaguy.easyminecraftgoals.internal.DamageIndicatorClamp;
import com.magmaguy.easyminecraftgoals.internal.PacketEntityInteractionManager;
import com.magmaguy.easyminecraftgoals.v26.CraftBukkitBridge;
import io.netty.channel.*;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for ServerboundInteractPacket to detect when players interact with packet-only entities.
 * Injects a Netty channel handler into each player's connection pipeline.
 * MC 26.1+ version - uses Record accessors directly (no obfuscation).
 */
public class PacketInteractionListener implements Listener {

    private static final String HANDLER_NAME = "emg_packet_interaction";
    private final Plugin plugin;
    private final Map<UUID, Channel> playerChannels = new ConcurrentHashMap<>();

    private static Field connectionField;
    private static Field channelField;

    // Reflection for outbound ClientboundLevelParticlesPacket clamping.
    // ClientboundLevelParticlesPacket is a regular (non-record) class with one
    // private final int field — `count` — so we mutate it in place rather than
    // rebuild the packet. Vanilla broadcasts share one packet instance across
    // every viewer's pipeline, so a single mutation suffices for all of them
    // (and concurrent re-mutations to the same cap value are idempotent).
    private static Field particleField;
    private static Field countField;

    static {
        for (Field f : ServerCommonPacketListenerImpl.class.getDeclaredFields()) {
            if (Connection.class.isAssignableFrom(f.getType())) {
                connectionField = f;
                connectionField.setAccessible(true);
                break;
            }
        }

        for (Field f : Connection.class.getDeclaredFields()) {
            if (Channel.class.isAssignableFrom(f.getType())) {
                channelField = f;
                channelField.setAccessible(true);
                break;
            }
        }

        // Discover the particle and count fields on ClientboundLevelParticlesPacket so
        // we can read the particle type and clamp the count without hardcoding obfuscated
        // names. Prefer a field literally named "count" (this runtime is Mojang-mapped);
        // otherwise fall back to the sole non-static int field. Crucially, if the count
        // field can't be resolved unambiguously we WARN — a previous silent no-op made
        // the damageIndicatorParticleCap setting look broken ("the cap does nothing")
        // when the field simply hadn't bound on that server version.
        try {
            Class<?> cls = ClientboundLevelParticlesPacket.class;
            java.util.List<Field> intFields = new java.util.ArrayList<>();
            for (Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == int.class) {
                    intFields.add(f);
                } else if (particleField == null && ParticleOptions.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    particleField = f;
                }
            }
            for (Field f : intFields) {
                if (f.getName().equals("count")) { countField = f; break; }
            }
            if (countField == null && intFields.size() == 1) countField = intFields.get(0);
            if (countField != null) countField.setAccessible(true);

            if (countField == null && intFields.size() > 1) {
                StringBuilder names = new StringBuilder();
                for (Field f : intFields) names.append(f.getName()).append(' ');
                Bukkit.getLogger().warning("[MagmaCore] Damage-indicator clamp: could not identify the particle count field on "
                        + cls.getName() + " (int fields: " + names.toString().trim()
                        + "). damageIndicatorParticleCap will have no effect on this server version — please report this.");
            } else if (countField == null || particleField == null) {
                Bukkit.getLogger().warning("[MagmaCore] Damage-indicator clamp disabled on this server version "
                        + "(count/particle field unresolved); damageIndicatorParticleCap will have no effect.");
            }
        } catch (Throwable t) {
            // If reflection fails the clamp becomes a no-op; the rest of the listener
            // (inbound interactions) keeps working — but make the failure visible.
            particleField = null;
            countField = null;
            Bukkit.getLogger().warning("[MagmaCore] Damage-indicator clamp failed to initialize: " + t
                    + "; damageIndicatorParticleCap will have no effect.");
        }
    }

    public PacketInteractionListener(Plugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        for (Player player : Bukkit.getOnlinePlayers()) {
            injectPlayer(player);
        }
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            uninjectPlayer(player);
        }
        playerChannels.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                injectPlayer(event.getPlayer());
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        uninjectPlayer(event.getPlayer());
    }

    private void injectPlayer(Player player) {
        try {
            ServerPlayer serverPlayer = CraftBukkitBridge.getServerPlayer(player);
            ServerGamePacketListenerImpl packetListener = serverPlayer.connection;

            Channel channel = getChannel(packetListener);
            if (channel == null) return;

            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }

            channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new PacketHandler(player));
            playerChannels.put(player.getUniqueId(), channel);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to inject packet handler for player " + player.getName() + ": " + e.getMessage());
        }
    }

    private Channel getChannel(ServerGamePacketListenerImpl packetListener) {
        try {
            if (connectionField != null && channelField != null) {
                Connection connection = (Connection) connectionField.get(packetListener);
                return (Channel) channelField.get(connection);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void uninjectPlayer(Player player) {
        Channel channel = playerChannels.remove(player.getUniqueId());
        if (channel != null && channel.pipeline().get(HANDLER_NAME) != null) {
            try {
                channel.pipeline().remove(HANDLER_NAME);
            } catch (Exception ignored) {
            }
        }
    }

    private class PacketHandler extends ChannelDuplexHandler {
        private final Player player;

        public PacketHandler(Player player) {
            this.player = player;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            // MC 26.1+ splits the old ServerboundInteractPacket: attacks now use
            // ServerboundAttackPacket, while ServerboundInteractPacket covers right-click only.
            int entityId = -1;
            boolean isAttack = false;
            boolean handled = false;

            if (msg instanceof ServerboundAttackPacket packet) {
                entityId = packet.entityId();
                isAttack = true;
                handled = true;
            } else if (msg instanceof ServerboundInteractPacket packet) {
                entityId = packet.entityId();
                isAttack = false;
                handled = true;
            }

            if (handled) {
                try {
                    if (PacketEntityInteractionManager.getInstance().getByEntityId(entityId) != null) {
                        final int capturedEntityId = entityId;
                        final boolean capturedIsAttack = isAttack;
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            PacketEntityInteractionManager.getInstance().handleInteraction(player, capturedEntityId, capturedIsAttack);
                        });
                        return;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof ClientboundLevelParticlesPacket particlesPacket) {
                msg = maybeClampDamageIndicator(particlesPacket);
            }
            super.write(ctx, msg, promise);
        }
    }

    /**
     * If the packet is a damage-indicator particle burst exceeding the configured
     * cap, returns a replacement packet carrying the capped count. The vanilla
     * packet stores its count in a {@code final} field, so mutating that field
     * reflectively is not guaranteed to affect the value later serialized by
     * the JVM. Rebuilding the packet makes the clamped value authoritative.
     */
    private static ClientboundLevelParticlesPacket maybeClampDamageIndicator(ClientboundLevelParticlesPacket packet) {
        int cap = DamageIndicatorClamp.getMaxParticles();
        if (cap <= 0) return packet;
        if (countField == null || particleField == null) return packet;

        try {
            ParticleOptions particle = (ParticleOptions) particleField.get(packet);
            if (particle == null || particle.getType() != ParticleTypes.DAMAGE_INDICATOR) return packet;

            int count = countField.getInt(packet);
            if (count <= cap) return packet;

            return new ClientboundLevelParticlesPacket(
                    particle, packet.isOverrideLimiter(), packet.alwaysShow(),
                    packet.getX(), packet.getY(), packet.getZ(),
                    packet.getXDist(), packet.getYDist(), packet.getZDist(),
                    packet.getMaxSpeed(), cap);
        } catch (Throwable t) {
            // Fail open: if reconstruction fails, the original packet still goes through.
            return packet;
        }
    }
}
