package com.magmaguy.easyminecraftgoals.v1_21_R7_common.packets;

import com.magmaguy.easyminecraftgoals.internal.DamageIndicatorClamp;
import com.magmaguy.easyminecraftgoals.internal.PacketEntityInteractionManager;
import com.magmaguy.easyminecraftgoals.internal.PacketInteractionContext;
import com.magmaguy.easyminecraftgoals.v1_21_R7_common.CraftBukkitBridge;
import io.netty.channel.*;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.inventory.EquipmentSlot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for ServerboundInteractPacket to detect when players interact with packet-only entities.
 * Injects a Netty channel handler into each player's connection pipeline.
 */
public class PacketInteractionListener implements Listener {

    // Per-plugin handler name. Multiple plugins shade EMG independently, each
    // installing its own netty handler. With a shared name, every plugin's
    // injection overwrites the previous one and only the last-enabled plugin's
    // handler stays in the pipeline — but each plugin's handler only checks
    // ITS OWN shaded PacketEntityInteractionManager singleton, so all the
    // overwritten plugins' packet entities silently stop receiving clicks.
    // Suffixing with the plugin name lets each plugin's handler co-exist.
    private final String handlerName;
    private final Plugin plugin;
    private final Map<UUID, Channel> playerChannels = new ConcurrentHashMap<>();

    // Reflection fields for accessing packet data
    private static Field entityIdField;
    private static Field interactionActionField;
    private static Object attackAction;
    private static Method isAttackMethod;
    private static final Map<Class<?>, InteractionActionDecoder> actionDecoders = new ConcurrentHashMap<>();
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
        // Resolve the packet fields structurally. The Spigot-mapped 1.21.11
        // runtime calls these fields b/c/e while Paper and Purpur expose the
        // Mojang names entityId/action/ATTACK_ACTION. Looking them up by shape
        // avoids hard links to ServerboundInteractPacket.Handler, whose nested
        // runtime name also differs between mapping namespaces.
        try {
            java.util.List<Field> entityIdCandidates = new java.util.ArrayList<>();
            java.util.List<Field> actionCandidates = new java.util.ArrayList<>();

            for (Field field : ServerboundInteractPacket.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;

                if (field.getType() == int.class) {
                    entityIdCandidates.add(field);
                } else if (!field.getType().isPrimitive()) {
                    actionCandidates.add(field);
                }
            }

            for (Field field : entityIdCandidates) {
                if (field.getName().equals("entityId")) {
                    entityIdField = field;
                    break;
                }
            }
            if (entityIdField == null && entityIdCandidates.size() == 1) {
                entityIdField = entityIdCandidates.get(0);
            }
            if (entityIdField != null) entityIdField.setAccessible(true);

            for (Field field : actionCandidates) {
                if (field.getName().equals("action")) {
                    interactionActionField = field;
                    break;
                }
            }
            if (interactionActionField == null && actionCandidates.size() == 1) {
                interactionActionField = actionCandidates.get(0);
            }
            if (interactionActionField != null) {
                interactionActionField.setAccessible(true);
                for (Field field : ServerboundInteractPacket.class.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            && field.getType() == interactionActionField.getType()) {
                        field.setAccessible(true);
                        attackAction = field.get(null);
                        break;
                    }
                }
            }

            // Paper-derived servers expose this helper. Keep it as a fallback
            // if a future packet layout makes structural field discovery
            // ambiguous, without requiring it on Spigot.
            try {
                Method method = ServerboundInteractPacket.class.getMethod("isAttack");
                if (method.getReturnType() == boolean.class) isAttackMethod = method;
            } catch (NoSuchMethodException ignored) {
            }

            if (entityIdField == null || (interactionActionField == null && isAttackMethod == null)) {
                Bukkit.getLogger().warning("[MagmaCore] Packet interaction decoding is incomplete for "
                        + ServerboundInteractPacket.class.getName()
                        + "; packet-only entity interactions may not work on this server version.");
            }
        } catch (ReflectiveOperationException | SecurityException exception) {
            Bukkit.getLogger().warning("[MagmaCore] Failed to initialize packet interaction decoding for "
                    + ServerboundInteractPacket.class.getName() + ": " + exception);
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

        // Discover the particle and count fields on ClientboundLevelParticlesPacket so
        // we can read the particle type and clamp the count without hardcoding obfuscated
        // names. Prefer a field literally named "count" (Mojang-mapped runtimes); otherwise
        // fall back to the sole non-static int field. Crucially, if the count field can't be
        // resolved unambiguously we WARN — a previous silent no-op made the
        // damageIndicatorParticleCap setting look broken ("the cap does nothing") when the
        // field simply hadn't bound on that server version.
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
                org.bukkit.Bukkit.getLogger().warning("[MagmaCore] Damage-indicator clamp: could not identify the particle count field on "
                        + cls.getName() + " (int fields: " + names.toString().trim()
                        + "). damageIndicatorParticleCap will have no effect on this server version — please report this.");
            } else if (countField == null || particleField == null) {
                org.bukkit.Bukkit.getLogger().warning("[MagmaCore] Damage-indicator clamp disabled on this server version "
                        + "(count/particle field unresolved); damageIndicatorParticleCap will have no effect.");
            }
        } catch (Throwable t) {
            // If reflection fails the clamp becomes a no-op; the rest of the listener
            // (inbound interactions) keeps working — but make the failure visible.
            particleField = null;
            countField = null;
            org.bukkit.Bukkit.getLogger().warning("[MagmaCore] Damage-indicator clamp failed to initialize: " + t
                    + "; damageIndicatorParticleCap will have no effect.");
        }
    }

    public PacketInteractionListener(Plugin plugin) {
        this.plugin = plugin;
        this.handlerName = "emg_packet_interaction_" + plugin.getName();
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
            ServerPlayer serverPlayer = CraftBukkitBridge.getServerPlayer(player);
            ServerGamePacketListenerImpl packetListener = serverPlayer.connection;

            // Access the channel through reflection
            Channel channel = getChannel(packetListener);

            if (channel == null) {
                return;
            }

            // Remove existing handler if present
            if (channel.pipeline().get(handlerName) != null) {
                channel.pipeline().remove(handlerName);
            }

            // Add our handler before the packet_handler
            channel.pipeline().addBefore("packet_handler", handlerName, new PacketHandler(player));
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
        if (channel != null && channel.pipeline().get(handlerName) != null) {
            try {
                channel.pipeline().remove(handlerName);
            } catch (Exception ignored) {
                // Channel might already be closed
            }
        }
    }

    /**
     * Netty handler that intercepts incoming and outgoing packets.
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
                    PacketInteractionContext interactionContext = getInteractionContext(packet);

                    PacketEntityInteractionManager interactionManager = PacketEntityInteractionManager.getInstance();
                    PacketEntityInteractionManager.Dispatch dispatch = interactionManager.captureDispatch(entityId);
                    if (dispatch != null) {
                        PacketEntityInteractionManager.PreparedInteraction prepared =
                                dispatch.prepare(interactionContext);
                        switch (prepared.decision()) {
                            case ROUTE -> {
                                Bukkit.getScheduler().runTask(plugin, () -> prepared.route(player));
                                return;
                            }
                            case CONSUME -> {
                                return;
                            }
                            case PASS -> {
                                // Forward to the native server handler.
                            }
                        }
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

    private static int getEntityId(ServerboundInteractPacket packet) {
        try {
            // Try record accessor first (MC 26.1+)
            try {
                return (int) ServerboundInteractPacket.class.getMethod("entityId").invoke(packet);
            } catch (NoSuchMethodException ignored) {
            }
            // Fall back to reflection field access
            if (entityIdField != null) {
                return entityIdField.getInt(packet);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    private static PacketInteractionContext getInteractionContext(ServerboundInteractPacket packet) {
        try {
            if (interactionActionField != null) {
                Object action = interactionActionField.get(packet);
                if (attackAction != null && action == attackAction) {
                    return PacketInteractionContext.attack();
                }
                if (attackAction == null && isAttackMethod != null
                        && (boolean) isAttackMethod.invoke(packet)) {
                    return PacketInteractionContext.attack();
                }
                return actionDecoders
                        .computeIfAbsent(action.getClass(), PacketInteractionListener::createActionDecoder)
                        .decode(action);
            }
            if (isAttackMethod != null && (boolean) isAttackMethod.invoke(packet)) {
                return PacketInteractionContext.attack();
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not decode interaction packet", exception);
        }
        throw new IllegalStateException("Interaction packet action is unavailable");
    }

    private static InteractionActionDecoder createActionDecoder(Class<?> actionClass) {
        Field handField = null;
        boolean interactionAt = false;
        for (Field field : actionClass.getDeclaredFields()) {
            if (InteractionHand.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                handField = field;
            } else if (Vec3.class.isAssignableFrom(field.getType())) {
                interactionAt = true;
            }
        }
        if (handField == null) {
            throw new IllegalStateException("Interaction action has no hand: " + actionClass.getName());
        }
        return new InteractionActionDecoder(handField, interactionAt);
    }

    private record InteractionActionDecoder(Field handField, boolean interactionAt) {
        private PacketInteractionContext decode(Object action) throws IllegalAccessException {
            InteractionHand hand = (InteractionHand) handField.get(action);
            EquipmentSlot equipmentSlot = hand == InteractionHand.MAIN_HAND
                    ? EquipmentSlot.HAND
                    : EquipmentSlot.OFF_HAND;
            return interactionAt
                    ? PacketInteractionContext.interactAt(equipmentSlot)
                    : PacketInteractionContext.interact(equipmentSlot);
        }
    }
}
