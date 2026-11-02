package com.magmaguy.easyminecraftgoals.internal;

import com.magmaguy.easyminecraftgoals.NMSManager;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Estimates each Java client's frame time from ping round trips, so callers can spare clients that are struggling.
 *
 * <p>A Java client answers a ping from its main thread, which reads the packet queue once per rendered frame, before
 * drawing it. A ping's round trip is therefore the network's plus the wait for the client's next frame, and that wait
 * averages half a frame. The fastest round trip of the last minute stands for the network alone, and twice the
 * distance from it to the typical recent round trip is the client's frame time. A slow PC, a low frame cap and a
 * backlog of packets all lengthen it.</p>
 *
 * <p>Each ping leaves at a random moment within its 200 ms slot, off the primary thread. Pings sent on server ticks
 * would reach a game that draws a frame every tick, or every few, at the same point of its frame each time, and that
 * point drifts as the two clocks slip, so the estimate would sweep instead of settling.</p>
 *
 * <p>Only players passed to {@link #track} are pinged. Ping ids sit far outside the 16-bit range anti-cheat
 * transactions use, and the version adapters drop the answers to them before the server sees them.</p>
 */
public final class ClientFrameLatency {
    // 'NB' in the high half; the low half counts pings.
    private static final int ID_TAG = 0x4E420000;
    private static final long PING_INTERVAL_NANOS = 200_000_000L;
    private static final long ANSWER_TIMEOUT_NANOS = 5_000_000_000L;
    // One ping every 200 ms: about 5 seconds make the typical round trip, about a minute the network's floor.
    static final int RECENT_SAMPLES = 25;
    static final int FLOOR_SAMPLES = 300;
    static final int MINIMUM_SAMPLES = 10;

    private static final Map<UUID, Client> clients = new ConcurrentHashMap<>();

    private ClientFrameLatency() {
    }

    /** Pings the player within the next 200 ms unless their last ping slot started under 200 ms ago. */
    public static void track(Player player) {
        UUID uuid = player.getUniqueId();
        Client client = clients.computeIfAbsent(uuid, ignored -> new Client());
        if (!client.claimSlot(System.nanoTime())) return;
        long delay = ThreadLocalRandom.current().nextLong(PING_INTERVAL_NANOS);
        CompletableFuture.delayedExecutor(delay, TimeUnit.NANOSECONDS).execute(() -> {
            // Quitting clears the client, so a ping due after its player left is dropped.
            if (clients.get(uuid) != client) return;
            int id = client.register(System.nanoTime());
            boolean sent;
            try {
                sent = NMSManager.getAdapter().sendPing(player, id);
            } catch (RuntimeException failure) {
                sent = false;
            }
            if (!sent) client.forget(id);
        });
    }

    /** The player's estimated frame time in milliseconds, or NaN until enough pings have been answered. */
    public static double frameMillis(UUID player) {
        Client client = clients.get(player);
        return client == null ? Double.NaN : client.frameMillis();
    }

    /**
     * Records a pong from a player's client. Returns true when it answers a ping sent here, and the caller then drops
     * it. Network thread.
     */
    public static boolean answer(UUID player, int id, long nanos) {
        if ((id & 0xFFFF0000) != ID_TAG) return false;
        Client client = clients.get(player);
        return client != null && client.answer(id, nanos);
    }

    public static void clearViewer(UUID player) {
        clients.remove(player);
    }

    public static void clear() {
        clients.clear();
    }

    /** One client's pending pings and round trips. */
    static final class Client {
        private final Map<Integer, Long> pending = new HashMap<>();
        private final double[] roundTrips = new double[FLOOR_SAMPLES];
        private int samples;
        private int next;
        private int sequence;
        private long lastPing = Long.MIN_VALUE;

        /** Whether a new ping slot starts now; false while the last one is under 200 ms old. */
        synchronized boolean claimSlot(long now) {
            if (lastPing != Long.MIN_VALUE && now - lastPing < PING_INTERVAL_NANOS) return false;
            lastPing = now;
            return true;
        }

        /** The id of a ping leaving now. */
        synchronized int register(long now) {
            Iterator<Long> sentTimes = pending.values().iterator();
            while (sentTimes.hasNext()) if (now - sentTimes.next() > ANSWER_TIMEOUT_NANOS) sentTimes.remove();
            sequence = (sequence + 1) & 0xFFFF;
            int id = ID_TAG | sequence;
            pending.put(id, now);
            return id;
        }

        synchronized void forget(int id) {
            pending.remove(id);
        }

        synchronized boolean answer(int id, long nanos) {
            Long sent = pending.remove(id);
            if (sent == null) return false;
            roundTrips[next] = (nanos - sent) / 1_000_000.0;
            next = (next + 1) % FLOOR_SAMPLES;
            if (samples < FLOOR_SAMPLES) samples++;
            return true;
        }

        synchronized double frameMillis() {
            if (samples < MINIMUM_SAMPLES) return Double.NaN;
            int recentCount = Math.min(samples, RECENT_SAMPLES);
            double[] recent = new double[recentCount];
            for (int i = 0; i < recentCount; i++)
                recent[i] = roundTrips[Math.floorMod(next - 1 - i, FLOOR_SAMPLES)];
            Arrays.sort(recent);
            double median = recentCount % 2 == 1
                    ? recent[recentCount / 2]
                    : (recent[recentCount / 2 - 1] + recent[recentCount / 2]) / 2;
            double floor = Double.MAX_VALUE;
            for (int i = 0; i < samples; i++) floor = Math.min(floor, roundTrips[i]);
            return 2 * (median - floor);
        }
    }
}
