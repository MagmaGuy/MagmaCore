package com.magmaguy.easyminecraftgoals.internal;

import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages interactions for client-targetable entity IDs.
 * Packet-only entities keep their legacy lookup while other interaction owners can register aliases.
 */
public class PacketEntityInteractionManager {

    private static PacketEntityInteractionManager instance;

    // Maps every client-targetable entity ID to its interaction owner. Packet-only
    // entities retain their legacy object lookup; aliases can supply a handler
    // without pretending to be a PacketEntityInterface.
    private final Map<Integer, InteractionEntry> entityIdMap = new ConcurrentHashMap<>();

    private PacketEntityInteractionManager() {
    }

    public static PacketEntityInteractionManager getInstance() {
        if (instance == null) {
            instance = new PacketEntityInteractionManager();
        }
        return instance;
    }

    /**
     * Registers a packet entity for interaction tracking.
     *
     * @param entityId The entity ID (from NMS entity)
     * @param entity   The packet entity
     */
    public void register(int entityId, PacketEntityInterface entity) {
        PacketEntityInterface packetEntity = Objects.requireNonNull(entity, "entity");
        entityIdMap.put(entityId, new InteractionEntry(
                packetEntity,
                context -> RoutingDecision.ROUTE,
                packetEntity::handleInteraction));
    }

    /**
     * Registers a general interaction handler for a client-targetable entity ID.
     *
     * <p>The returned registration owns exactly the entry created by this call.
     * Closing an older registration after the same numeric ID has been reused
     * therefore cannot remove the replacement.</p>
     *
     * @param entityId entity ID sent by the client
     * @param handler  handler receiving the player and whether the click was an attack
     * @return lifecycle registration for this exact mapping
     */
    public Registration registerHandler(int entityId, InteractionHandler handler) {
        InteractionHandler legacyHandler = Objects.requireNonNull(handler, "handler");
        return registerContextHandler(
                entityId,
                context -> RoutingDecision.ROUTE,
                (player, context) -> legacyHandler.handle(player, context.isAttack()));
    }

    /**
     * Registers a contextual handler that routes and consumes every interaction.
     */
    public Registration registerContextHandler(int entityId, ContextualInteractionHandler handler) {
        return registerContextHandler(entityId, context -> RoutingDecision.ROUTE, handler);
    }

    /**
     * Registers a contextual handler with packet-routing policy.
     *
     * <p>The routing policy is evaluated on the player's Netty event-loop thread.
     * It must be fast, thread-safe, and must not access Bukkit main-thread-only
     * state. A routed handler is invoked later on the Bukkit main thread by the
     * maintained packet listeners.</p>
     *
     * @param entityId     entity ID sent by the client
     * @param routingPolicy policy deciding whether to route, consume, or pass the packet
     * @param handler      contextual handler for routed packets
     * @return lifecycle registration for this exact mapping
     */
    public Registration registerContextHandler(
            int entityId,
            InteractionRoutingPolicy routingPolicy,
            ContextualInteractionHandler handler) {
        InteractionEntry entry = new InteractionEntry(
                null,
                Objects.requireNonNull(routingPolicy, "routingPolicy"),
                Objects.requireNonNull(handler, "handler"));
        entityIdMap.put(entityId, entry);
        return new Registration(entityId, entry);
    }

    /**
     * Unregisters a packet entity from interaction tracking.
     *
     * @param entityId The entity ID to unregister
     */
    public void unregister(int entityId) {
        entityIdMap.remove(entityId);
    }

    /**
     * Gets a packet entity by its entity ID.
     *
     * @param entityId The entity ID
     * @return The packet entity, or null if not found
     */
    public PacketEntityInterface getByEntityId(int entityId) {
        InteractionEntry entry = entityIdMap.get(entityId);
        return entry == null ? null : entry.packetEntity();
    }

    /**
     * Returns whether any interaction owner is registered for this entity ID.
     * Unlike {@link #getByEntityId(int)}, this includes general handler aliases.
     */
    public boolean isRegistered(int entityId) {
        return entityIdMap.containsKey(entityId);
    }

    /**
     * Captures the exact interaction mapping currently registered for an entity ID.
     *
     * <p>Packet listeners use this before leaving the network thread. The captured
     * dispatch refuses to invoke its handler if the numeric entity ID is removed or
     * replaced before the scheduled Bukkit task runs.</p>
     *
     * @param entityId entity ID received from the client
     * @return a dispatch bound to the current mapping, or null when none is registered
     */
    public Dispatch captureDispatch(int entityId) {
        InteractionEntry entry = entityIdMap.get(entityId);
        return entry == null ? null : new Dispatch(entityId, entry);
    }

    /**
     * Handles an interaction packet from a player.
     * Called by the packet interceptor when a ServerboundInteractPacket is received.
     *
     * @param player   The player who sent the interaction
     * @param entityId The entity ID being interacted with
     * @param isAttack True if this was an attack action, false if interact
     * @return true if the interaction was handled by a registered owner, false otherwise
     */
    public boolean handleInteraction(Player player, int entityId, boolean isAttack) {
        return handleInteraction(
                player,
                entityId,
                isAttack ? PacketInteractionContext.attack()
                        : PacketInteractionContext.interact(EquipmentSlot.HAND));
    }

    /**
     * Handles a decoded interaction immediately while preserving its action and hand.
     */
    public boolean handleInteraction(
            Player player, int entityId, PacketInteractionContext context) {
        InteractionEntry entry = entityIdMap.get(entityId);
        if (entry == null) {
            return false;
        }

        entry.handler().handle(player, Objects.requireNonNull(context, "context"));
        return true;
    }

    /**
     * Clears all registered entities.
     * Called on shutdown.
     */
    public void shutdown() {
        entityIdMap.clear();
    }

    @FunctionalInterface
    public interface InteractionHandler {
        void handle(Player player, boolean isAttack);
    }

    @FunctionalInterface
    public interface ContextualInteractionHandler {
        void handle(Player player, PacketInteractionContext context);
    }

    @FunctionalInterface
    public interface InteractionRoutingPolicy {
        RoutingDecision decide(PacketInteractionContext context);
    }

    public enum RoutingDecision {
        /** Consume the native packet and schedule the contextual handler. */
        ROUTE,
        /** Consume the native packet without invoking the contextual handler. */
        CONSUME,
        /** Forward the packet to the native server handler. */
        PASS
    }

    public final class Registration implements AutoCloseable {
        private final int entityId;
        private final InteractionEntry entry;

        private Registration(int entityId, InteractionEntry entry) {
            this.entityId = entityId;
            this.entry = entry;
        }

        @Override
        public void close() {
            entityIdMap.remove(entityId, entry);
        }
    }

    public final class Dispatch {
        private final int entityId;
        private final InteractionEntry entry;

        private Dispatch(int entityId, InteractionEntry entry) {
            this.entityId = entityId;
            this.entry = entry;
        }

        /**
         * Invokes the captured handler only while its exact mapping is still current.
         *
         * @return true when the captured mapping handled the interaction
         */
        public boolean handle(Player player, boolean isAttack) {
            return handle(
                    player,
                    isAttack ? PacketInteractionContext.attack()
                            : PacketInteractionContext.interact(EquipmentSlot.HAND));
        }

        /**
         * Invokes the captured handler directly with decoded packet context.
         */
        public boolean handle(Player player, PacketInteractionContext context) {
            if (entityIdMap.get(entityId) != entry) {
                return false;
            }

            entry.handler().handle(player, Objects.requireNonNull(context, "context"));
            return true;
        }

        /**
         * Resolves packet routing on the calling network thread. The returned
         * interaction carries the already-resolved decision so the routing policy
         * is not evaluated again on the Bukkit main thread.
         */
        public PreparedInteraction prepare(PacketInteractionContext context) {
            PacketInteractionContext interactionContext = Objects.requireNonNull(context, "context");
            if (entityIdMap.get(entityId) != entry) {
                return new PreparedInteraction(entityId, entry, interactionContext, RoutingDecision.PASS);
            }

            RoutingDecision decision = Objects.requireNonNull(
                    entry.routingPolicy().decide(interactionContext),
                    "routingPolicy decision");
            return new PreparedInteraction(entityId, entry, interactionContext, decision);
        }
    }

    public final class PreparedInteraction {
        private final int entityId;
        private final InteractionEntry entry;
        private final PacketInteractionContext context;
        private final RoutingDecision decision;

        private PreparedInteraction(
                int entityId,
                InteractionEntry entry,
                PacketInteractionContext context,
                RoutingDecision decision) {
            this.entityId = entityId;
            this.entry = entry;
            this.context = context;
            this.decision = decision;
        }

        public RoutingDecision decision() {
            return decision;
        }

        /**
         * Invokes a routed interaction only while its exact registration remains current.
         * This method is intended to run on the Bukkit main thread.
         */
        public boolean route(Player player) {
            if (decision != RoutingDecision.ROUTE || entityIdMap.get(entityId) != entry) {
                return false;
            }

            entry.handler().handle(player, context);
            return true;
        }
    }

    private record InteractionEntry(
            PacketEntityInterface packetEntity,
            InteractionRoutingPolicy routingPolicy,
            ContextualInteractionHandler handler) {
    }
}
