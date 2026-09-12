package com.magmaguy.magmacore.util;

import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** Registers one platform event against the caller's existing listener lifecycle. */
public final class KnockbackEvents {
    private KnockbackEvents() { }

    public static void registerEntityScale(Plugin plugin, Listener listener,
                                           BiFunction<Entity, Entity, Double> multiplier) {
        Class<? extends Event> type = eventType(
                "com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent",
                org.bukkit.event.entity.EntityKnockbackByEntityEvent.class);
        boolean paper = !type.getName().startsWith("org.bukkit.");
        try {
            Method source = type.getMethod(paper ? "getHitBy" : "getSourceEntity");
            Method vector = type.getMethod(paper ? "getKnockback" : "getFinalKnockback");
            Method setter = type.getMethod(paper ? "setKnockback" : "setFinalKnockback", Vector.class);
            plugin.getServer().getPluginManager().registerEvent(type, listener, EventPriority.HIGHEST,
                    (ignored, event) -> {
                        if (!type.isInstance(event)) return;
                        try {
                            double scale = multiplier.apply(((EntityEvent) event).getEntity(), (Entity) source.invoke(event));
                            if (Math.abs(scale - 1D) >= 1.0E-9D)
                                setter.invoke(event, ((Vector) vector.invoke(event)).clone().multiply(scale));
                        } catch (ReflectiveOperationException failure) {
                            throw new EventException(failure);
                        }
                    }, plugin, true);
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException("Unsupported knockback event API: " + type.getName(), failure);
        }
    }

    public static void registerCancellation(Plugin plugin, Listener listener, Predicate<Entity> cancel) {
        Class<? extends Event> type = eventType("io.papermc.paper.event.entity.EntityKnockbackEvent",
                org.bukkit.event.entity.EntityKnockbackEvent.class);
        plugin.getServer().getPluginManager().registerEvent(type, listener, EventPriority.HIGHEST,
                (ignored, event) -> {
                    if (type.isInstance(event) && cancel.test(((EntityEvent) event).getEntity()))
                        ((Cancellable) event).setCancelled(true);
                }, plugin, true);
    }

    private static Class<? extends Event> eventType(String paperName, Class<? extends Event> spigot) {
        try {
            return Class.forName(paperName).asSubclass(Event.class);
        } catch (ClassNotFoundException absentOnSpigot) {
            return spigot;
        }
    }
}
