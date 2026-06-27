package com.magmaguy.easyminecraftgoals.internal;

/**
 * Lets the consuming plugin observe packets that are sent <b>directly</b> (via
 * {@code connection.send}), outside of any {@link AbstractPacketBundle}. The FMM packet
 * sampler registers an observer so its per-tick report includes these unbundled sends
 * (hitbox teleports, head rotations, mounts, etc.) that the bundle-wrapping sampler can't see.
 *
 * <p>No-op unless an observer is registered. The observer itself is expected to be cheap and
 * to ignore sends when it isn't actively sampling.</p>
 */
public final class PacketSendObserver {

    /** Receives one call per direct broadcast: the packet and how many viewers it went to. */
    public interface Observer {
        void onSend(Object packet, int viewerCount);
    }

    private static volatile Observer impl;

    private PacketSendObserver() {
    }

    public static void setImpl(Observer observer) {
        impl = observer;
    }

    public static boolean isActive() {
        return impl != null;
    }

    public static void observe(Object packet, int viewerCount) {
        Observer observer = impl;
        if (observer == null || packet == null || viewerCount <= 0) return;
        try {
            observer.onSend(packet, viewerCount);
        } catch (Throwable ignored) {
            // diagnostics must never break packet sending
        }
    }
}
