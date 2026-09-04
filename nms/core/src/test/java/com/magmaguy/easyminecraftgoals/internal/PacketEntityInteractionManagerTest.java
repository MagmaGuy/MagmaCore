package com.magmaguy.easyminecraftgoals.internal;

import org.bukkit.Location;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketEntityInteractionManagerTest {

    private final PacketEntityInteractionManager manager = PacketEntityInteractionManager.getInstance();

    @AfterEach
    void cleanUp() {
        manager.shutdown();
    }

    @Test
    void generalHandlerReceivesAttackAndInteract() {
        List<Boolean> attacks = new ArrayList<>();
        manager.registerHandler(41, (player, isAttack) -> attacks.add(isAttack));
        PacketEntityInteractionManager.Dispatch dispatch = manager.captureDispatch(41);
        assertNotNull(dispatch);

        assertTrue(manager.isRegistered(41));
        assertNull(manager.getByEntityId(41));
        assertTrue(dispatch.handle(null, true));
        assertTrue(dispatch.handle(null, false));
        assertEquals(List.of(true, false), attacks);
    }

    @Test
    void contextualHandlerPreservesActionAndHand() {
        List<PacketInteractionContext> contexts = new ArrayList<>();
        manager.registerContextHandler(41, (player, context) -> contexts.add(context));
        PacketEntityInteractionManager.Dispatch dispatch = manager.captureDispatch(41);
        assertNotNull(dispatch);

        assertTrue(dispatch.handle(null, PacketInteractionContext.attack()));
        assertTrue(dispatch.handle(null, PacketInteractionContext.interact(EquipmentSlot.HAND)));
        assertTrue(dispatch.handle(null, PacketInteractionContext.interact(EquipmentSlot.OFF_HAND)));
        assertTrue(dispatch.handle(null, PacketInteractionContext.interactAt(EquipmentSlot.HAND)));
        assertTrue(dispatch.handle(null, PacketInteractionContext.interactAt(EquipmentSlot.OFF_HAND)));

        assertEquals(List.of(
                PacketInteractionContext.attack(),
                PacketInteractionContext.interact(EquipmentSlot.HAND),
                PacketInteractionContext.interact(EquipmentSlot.OFF_HAND),
                PacketInteractionContext.interactAt(EquipmentSlot.HAND),
                PacketInteractionContext.interactAt(EquipmentSlot.OFF_HAND)
        ), contexts);
    }

    @Test
    void legacyHandlerAdaptsContextToAttackBoolean() {
        List<Boolean> attacks = new ArrayList<>();
        manager.registerHandler(41, (player, isAttack) -> attacks.add(isAttack));
        PacketEntityInteractionManager.Dispatch dispatch = manager.captureDispatch(41);
        assertNotNull(dispatch);

        assertTrue(dispatch.handle(null, PacketInteractionContext.attack()));
        assertTrue(dispatch.handle(null, PacketInteractionContext.interact(EquipmentSlot.OFF_HAND)));
        assertTrue(manager.handleInteraction(
                null, 41, PacketInteractionContext.interactAt(EquipmentSlot.HAND)));

        assertEquals(List.of(true, false, false), attacks);
    }

    @Test
    void routingPolicyProducesPassConsumeAndPreparedRouteWithoutRunningHandlerOnNetworkThread() {
        AtomicInteger policyCalls = new AtomicInteger();
        List<PacketInteractionContext> routedContexts = new ArrayList<>();
        manager.registerContextHandler(
                41,
                context -> {
                    policyCalls.incrementAndGet();
                    if (context.hand() == EquipmentSlot.OFF_HAND) {
                        return PacketEntityInteractionManager.RoutingDecision.PASS;
                    }
                    if (context.action() == PacketInteractionContext.Action.ATTACK) {
                        return PacketEntityInteractionManager.RoutingDecision.CONSUME;
                    }
                    return PacketEntityInteractionManager.RoutingDecision.ROUTE;
                },
                (player, context) -> routedContexts.add(context));
        PacketEntityInteractionManager.Dispatch dispatch = manager.captureDispatch(41);
        assertNotNull(dispatch);

        PacketEntityInteractionManager.PreparedInteraction pass =
                dispatch.prepare(PacketInteractionContext.interact(EquipmentSlot.OFF_HAND));
        PacketEntityInteractionManager.PreparedInteraction consume =
                dispatch.prepare(PacketInteractionContext.attack());
        PacketEntityInteractionManager.PreparedInteraction route =
                dispatch.prepare(PacketInteractionContext.interactAt(EquipmentSlot.HAND));

        assertEquals(PacketEntityInteractionManager.RoutingDecision.PASS, pass.decision());
        assertEquals(PacketEntityInteractionManager.RoutingDecision.CONSUME, consume.decision());
        assertEquals(PacketEntityInteractionManager.RoutingDecision.ROUTE, route.decision());
        assertEquals(3, policyCalls.get());
        assertEquals(List.of(), routedContexts);

        assertFalse(pass.route(null));
        assertFalse(consume.route(null));
        assertTrue(route.route(null));
        assertEquals(3, policyCalls.get());
        assertEquals(List.of(PacketInteractionContext.interactAt(EquipmentSlot.HAND)), routedContexts);
    }

    @Test
    void legacyRegistrationPreparesEveryInteractionForRouting() {
        manager.registerHandler(41, (player, isAttack) -> {
        });
        PacketEntityInteractionManager.Dispatch dispatch = manager.captureDispatch(41);
        assertNotNull(dispatch);

        assertEquals(
                PacketEntityInteractionManager.RoutingDecision.ROUTE,
                dispatch.prepare(PacketInteractionContext.interact(EquipmentSlot.OFF_HAND)).decision());
    }

    @Test
    void delayedDispatchCannotReachAReplacementUsingTheSameEntityId() {
        List<String> recipients = new ArrayList<>();
        manager.registerHandler(41, (player, isAttack) -> recipients.add("original"));
        PacketEntityInteractionManager.Dispatch dispatch = manager.captureDispatch(41);
        assertNotNull(dispatch);
        PacketEntityInteractionManager.PreparedInteraction prepared =
                dispatch.prepare(PacketInteractionContext.attack());

        manager.registerHandler(41, (player, isAttack) -> recipients.add("replacement"));

        assertFalse(dispatch.handle(null, true));
        assertFalse(prepared.route(null));
        assertEquals(List.of(), recipients);
        assertTrue(manager.handleInteraction(null, 41, true));
        assertEquals(List.of("replacement"), recipients);
    }

    @Test
    void closedRegistrationFallsThrough() {
        List<Boolean> attacks = new ArrayList<>();
        PacketEntityInteractionManager.Registration registration =
                manager.registerHandler(41, (player, isAttack) -> attacks.add(isAttack));
        PacketEntityInteractionManager.Dispatch dispatch = manager.captureDispatch(41);
        assertNotNull(dispatch);
        assertTrue(manager.isRegistered(41));

        registration.close();
        registration.close();

        assertFalse(manager.isRegistered(41));
        assertFalse(manager.handleInteraction(null, 41, true));
        assertFalse(dispatch.handle(null, true));
        assertEquals(List.of(), attacks);
    }

    @Test
    void staleRegistrationCannotCloseItsReplacement() {
        List<String> recipients = new ArrayList<>();
        PacketEntityInteractionManager.Registration original =
                manager.registerHandler(41, (player, isAttack) -> recipients.add("original"));
        PacketEntityInteractionManager.Registration replacement =
                manager.registerHandler(41, (player, isAttack) -> recipients.add("replacement"));

        original.close();

        assertTrue(manager.isRegistered(41));
        assertTrue(manager.handleInteraction(null, 41, false));
        assertEquals(List.of("replacement"), recipients);

        replacement.close();
        assertFalse(manager.isRegistered(41));
    }

    @Test
    void shutdownClearsHandlersAndCapturedDispatches() {
        List<Boolean> attacks = new ArrayList<>();
        manager.registerHandler(41, (player, isAttack) -> attacks.add(isAttack));
        PacketEntityInteractionManager.Dispatch dispatch = manager.captureDispatch(41);
        assertNotNull(dispatch);

        manager.shutdown();

        assertFalse(manager.isRegistered(41));
        assertFalse(manager.handleInteraction(null, 41, true));
        assertFalse(dispatch.handle(null, true));
        assertEquals(List.of(), attacks);
    }

    @Test
    void legacyPacketEntityLookupAndDispatchRemainCompatible() {
        AtomicInteger interactions = new AtomicInteger();
        PacketEntityInterface packetEntity = new TestPacketEntity(interactions);
        PacketEntityInteractionManager.Registration alias =
                manager.registerHandler(41, (player, isAttack) -> {
                });

        manager.register(41, packetEntity);
        alias.close();

        assertTrue(manager.isRegistered(41));
        assertSame(packetEntity, manager.getByEntityId(41));
        assertTrue(manager.handleInteraction(null, 41, true));
        assertEquals(1, interactions.get());

        manager.unregister(41);
        assertFalse(manager.isRegistered(41));
        assertNull(manager.getByEntityId(41));
    }

    @Test
    void packetEntityRegistrationPreservesExactInteractionContext() {
        List<PacketInteractionContext> contexts = new ArrayList<>();
        PacketEntityInterface packetEntity = new TestPacketEntity(new AtomicInteger()) {
            @Override
            public void handleInteraction(
                    org.bukkit.entity.Player player,
                    PacketInteractionContext context) {
                contexts.add(context);
            }
        };
        manager.register(41, packetEntity);
        PacketEntityInteractionManager.Dispatch dispatch = manager.captureDispatch(41);
        assertNotNull(dispatch);

        PacketInteractionContext offHandInteractAt =
                PacketInteractionContext.interactAt(EquipmentSlot.OFF_HAND);
        assertTrue(dispatch.handle(null, offHandInteractAt));

        assertEquals(List.of(offHandInteractAt), contexts);
    }

    private static class TestPacketEntity implements PacketEntityInterface {
        private final AtomicInteger interactions;

        private TestPacketEntity(AtomicInteger interactions) {
            this.interactions = interactions;
        }

        @Override
        public void addRemoveCallback(Runnable callback) {
        }

        @Override
        public void displayTo(UUID uuid) {
        }

        @Override
        public void hideFrom(UUID uuid) {
        }

        @Override
        public void remove() {
        }

        @Override
        public void setVisible(boolean visible) {
        }

        @Override
        public Location getLocation() {
            return null;
        }

        @Override
        public UUID getUniqueId() {
            return new UUID(0, 0);
        }

        @Override
        public void teleport(Location location) {
        }

        @Override
        public void addViewer(UUID uuid) {
        }

        @Override
        public void removeViewer(UUID uuid) {
        }

        @Override
        public boolean hasViewers() {
            return false;
        }

        @Override
        public AbstractPacketBundle createPacketBundle() {
            return null;
        }

        @Override
        public void handleInteraction(org.bukkit.entity.Player player, boolean isAttack) {
            interactions.incrementAndGet();
        }
    }
}
