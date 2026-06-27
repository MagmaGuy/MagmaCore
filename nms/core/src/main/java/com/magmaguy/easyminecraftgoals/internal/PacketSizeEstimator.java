package com.magmaguy.easyminecraftgoals.internal;

/**
 * Bridge that lets the version-agnostic consumer (e.g. FreeMinecraftModels' packet sampler)
 * ask the active NMS module for the REAL serialized wire size of a packet, without the
 * consumer needing any NMS imports.
 *
 * <p>The NMS module registers a {@link PacketSizeFunction} at adapter init via {@link #setImpl}.
 * Callers use {@link #sizeOf}; if no implementation is registered (or it fails) they get
 * {@code -1} and should fall back to their own estimate.</p>
 */
public final class PacketSizeEstimator {

    /** Encodes a single NMS packet and returns its serialized byte length, or -1 if unmeasurable. */
    public interface PacketSizeFunction {
        int sizeOf(Object packet);
    }

    private static volatile PacketSizeFunction impl;

    private PacketSizeEstimator() {
    }

    public static void setImpl(PacketSizeFunction function) {
        impl = function;
    }

    public static boolean hasImpl() {
        return impl != null;
    }

    /**
     * @return the packet's real serialized size in bytes, or -1 if no implementation is
     * registered or the packet could not be measured.
     */
    public static int sizeOf(Object packet) {
        PacketSizeFunction function = impl;
        if (function == null) return -1;
        try {
            return function.sizeOf(packet);
        } catch (Throwable t) {
            return -1;
        }
    }
}
