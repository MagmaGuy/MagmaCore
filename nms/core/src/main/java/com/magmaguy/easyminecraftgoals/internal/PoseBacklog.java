package com.magmaguy.easyminecraftgoals.internal;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Which players' connections are behind on poses.
 *
 * <p>The server never holds packets back: whatever it sends a player waits in that player's connection until the
 * player's link, or their game, takes it. A connection that carries less than the server sends therefore falls
 * further behind every tick, and the player sees the world, their own position included, later and later. A pose is a
 * full state that the next one replaces, so a pose waiting behind an older one is already stale. A player whose last
 * bundle of poses has not yet left the server skips new poses until it has, and so receives poses as fast as their
 * connection carries them. Everything else, such as spawns, removals and mounts, still goes out.</p>
 *
 * <p>The packet bundles mark a player's bundle as leaving when it carries poses and report when the connection has
 * written it. The display entities ask {@link #behind} before giving a player a pose. Thread-safe: bundles are sent
 * from the model clock and written on the network threads.</p>
 */
public final class PoseBacklog {

    private static final Map<UUID, AtomicInteger> leaving = new ConcurrentHashMap<>();

    private PoseBacklog() {
    }

    /** Whether the player's last bundle of poses is still waiting to leave the server, so new poses skip them. */
    public static boolean behind(UUID player) {
        if (!PacketEntityTuning.skipPosesWhileBehind) return false;
        AtomicInteger bundles = leaving.get(player);
        return bundles != null && bundles.get() > 0;
    }

    /**
     * Marks a bundle carrying poses as leaving for this player. Run the returned callback once the connection has
     * written it or failed to; later runs do nothing.
     */
    public static Runnable leaving(UUID player) {
        AtomicInteger bundles = leaving.computeIfAbsent(player, ignored -> new AtomicInteger());
        bundles.incrementAndGet();
        AtomicBoolean left = new AtomicBoolean();
        return () -> {
            if (left.compareAndSet(false, true)) bundles.decrementAndGet();
        };
    }

    public static void clearViewer(UUID player) {
        leaving.remove(player);
    }

    public static void clear() {
        leaving.clear();
    }
}
