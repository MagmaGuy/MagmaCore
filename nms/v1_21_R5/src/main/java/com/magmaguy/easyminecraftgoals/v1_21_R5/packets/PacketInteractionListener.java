package com.magmaguy.easyminecraftgoals.v1_21_R5.packets;

import com.magmaguy.easyminecraftgoals.internal.DamageIndicatorClamp;
import com.magmaguy.easyminecraftgoals.internal.PacketEntityInteractionManager;
import io.netty.channel.*;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_21_R5.entity.CraftPlayer;
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
 */
public class PacketInteractionListener implements Listener {

    private static final String HANDLER_NAME = "emg_packet_interaction";
    private final Plugin plugin;
    private final Map<UUID, Channel> playerChannels = new ConcurrentHashMap<>();

    // Reflection fields for accessing packet data
    private static Field entityIdField;
    private static Field actionField;
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
        try {
            // Get entityId field from ServerboundInteractPacket
            entityIdField = ServerboundInteractPacket.class.getDeclaredField("entityId");
            entityIdField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            try {
                entityIdField = ServerboundInteractPacket.class.getDeclaredField("a");
                entityIdField.setAccessible(true);
            } catch (NoSuchFieldException ex) {
                ex.printStackTrace();
            }
        }

        try {
            // Get action field from ServerboundInteractPacket
            actionField = ServerboundInteractPacket.class.getDeclaredField("action");
            actionField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            try {
                actionField = ServerboundInteractPacket.class.getDeclaredField("b");
                actionField.setAccessible(true);
            } catch (NoSuchFieldException ex) {
                ex.printStackTrace();
            }
        }

        // Find Connection field by type to handle obfuscated names
        for (Field f : ServerCommonPacketListenerImpl.class.getDeclaredFields()) {
            if (Connection.class.isAssignableFrom(f.getType())) {
                connectionField = f;
                connectionField.setAccessible(true);
                break;
            }
        }

        // Find channel field by type to handle obfuscated names
        for (Field f : Connection.class.getDeclaredFields()) {
            if (Channel.class.isAssignableFrom(f.getType())) {
                channelField = f;
                channelField.setAccessible(true);
                break;
            }
        }

        // Discover the particle and count fields by type so we can read the
        // particle type and mutate the count without coupling to obfuscated
        // field names. ClientboundLevelParticlesPacket has exactly one int
        // field (count) and one ParticleOptions field (particle), so finding
        // by type is unambiguous.
        try {
            Class<?> cls = ClientboundLevelParticlesPacket.class;
            for (Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (countField == null && f.getType() == int.class) {
                    f.setAccessible(true);
                    countField = f;
                } else if (particleField == null && ParticleOptions.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    particleField = f;
                }
            }
        } catch (Throwable t) {
            // If reflection fails, the clamp simply becomes a no-op; the rest of
            // the listener (inbound interactions) keeps working.
            particleField = null;
            countField = null;
        }
    }

    public PacketInteractionListener(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Initializes the listener and injects into all online players.
     */
    public void initialize() {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Inject into all currently online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            injectPlayer(player);
        }
    }

    /**
     * Shuts down the listener and removes all injected handlers.
     */
    public void shutdown() {
        // Remove handlers from all players
        for (Player player : Bukkit.getOnlinePlayers()) {
            uninjectPlayer(player);
        }
        playerChannels.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Delay injection by 1 tick to ensure connection is fully established
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
            ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
            ServerGamePacketListenerImpl packetListener = serverPlayer.connection;

            // Access the channel through reflection
            Channel channel = getChannel(packetListener);

            if (channel == null) {
                return;
            }

            // Remove existing handler if present
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }

            // Add our handler before the packet_handler
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
                // Channel might already be closed
            }
        }
    }

    /**
     * Netty handler that intercepts incoming packets.
     */
    private class PacketHandler extends ChannelDuplexHandler {
        private final Player player;

        public PacketHandler(Player player) {
            this.player = player;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ServerboundInteractPacket packet) {
                try {
                    int entityId = getEntityId(packet);
                    boolean isAttack = isAttackAction(packet);

                    // Check if this entity ID belongs to a packet entity
                    if (PacketEntityInteractionManager.getInstance().getByEntityId(entityId) != null) {
                        // This is a packet entity - handle on main thread and don't pass to server
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            PacketEntityInteractionManager.getInstance().handleInteraction(player, entityId, isAttack);
                        });
                        return; // Don't pass the packet to the server
                    }
                } catch (Exception e) {
                    // If anything goes wrong, let the packet through normally
                    e.printStackTrace();
                }
            }

            // Pass packet to next handler
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof ClientboundLevelParticlesPacket particlesPacket) {
                maybeClampDamageIndicator(particlesPacket);
            }
            super.write(ctx, msg, promise);
        }
    }

    /**
     * If the packet is a damage-indicator particle burst exceeding the configured
     * cap, mutates its {@code count} field in place to the cap. No-op otherwise.
     * Mutation is safe because the packet object is broadcast-shared across all
     * viewers' pipelines and we want every viewer to see the clamped value.
     */
    private static void maybeClampDamageIndicator(ClientboundLevelParticlesPacket packet) {
        int cap = DamageIndicatorClamp.getMaxParticles();
        if (cap <= 0) return;
        if (countField == null || particleField == null) return;

        try {
            ParticleOptions particle = (ParticleOptions) particleField.get(packet);
            if (particle == null || particle.getType() != ParticleTypes.DAMAGE_INDICATOR) return;

            int count = countField.getInt(packet);
            if (count <= cap) return;

            countField.setInt(packet, cap);
        } catch (Throwable t) {
            // Fail open: if mutation fails, the original packet still goes through.
        }
    }

    private static int getEntityId(ServerboundInteractPacket packet) {
        try {
            if (entityIdField != null) {
                return entityIdField.getInt(packet);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    private static boolean isAttackAction(ServerboundInteractPacket packet) {
        boolean[] isAttack = {false};
        packet.dispatch(new ServerboundInteractPacket.Handler() {
            @Override
            public void onAttack() {
                isAttack[0] = true;
            }

            @Override
            public void onInteraction(InteractionHand hand) {
            }

            @Override
            public void onInteraction(InteractionHand hand, Vec3 interactionLocation) {
            }
        });
        return isAttack[0];
    }
}
