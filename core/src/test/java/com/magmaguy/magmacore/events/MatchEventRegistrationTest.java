package com.magmaguy.magmacore.events;

import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchEventRegistrationTest {
    @Test
    void joinEventSupportsBukkitListenerRegistration() throws ReflectiveOperationException {
        assertBukkitHandlerRegistration(new MatchJoinEvent(null, null));
    }

    @Test
    void instantiateEventSupportsBukkitListenerRegistration() throws ReflectiveOperationException {
        assertBukkitHandlerRegistration(new MatchInstantiateEvent(null));
    }

    @Test
    void leaveEventSupportsBukkitListenerRegistration() throws ReflectiveOperationException {
        assertBukkitHandlerRegistration(new MatchLeaveEvent(null, null));
    }

    private static void assertBukkitHandlerRegistration(Event event) throws ReflectiveOperationException {
        // Bukkit locates custom event handlers through this public static method.
        var registrationMethod = event.getClass().getMethod("getHandlerList");
        assertTrue(Modifier.isStatic(registrationMethod.getModifiers()));
        assertSame(event.getHandlers(), registrationMethod.invoke(null));
    }
}
