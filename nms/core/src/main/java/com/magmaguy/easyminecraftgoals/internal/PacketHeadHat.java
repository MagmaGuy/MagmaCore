package com.magmaguy.easyminecraftgoals.internal;

import com.magmaguy.easyminecraftgoals.NMSManager;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entity ids that wear one hat item in their head slot, as Java viewers see them. The server never equips the hat,
 * so the mob still burns in daylight and drops nothing: the outbound packet hook shows it in place of an empty head
 * slot in the equipment it sends, and sends it right after the entity spawns, since a spawn with an empty head sends
 * no equipment at all. A real helmet stays visible over the hat. Bedrock viewers are skipped, because Geyser would
 * draw the hat as a plain item.
 */
public final class PacketHeadHat {
    // The hat as a native item stack, or null while no hat is set.
    private static volatile Object hat;
    // Hatted ids, sorted. A change replaces the array, so the packet hook reads one without a lock or a boxed lookup
    // for every packet it checks.
    private static volatile int[] hatted = new int[0];
    private static final Set<UUID> SKIPPED_VIEWERS = ConcurrentHashMap.newKeySet();

    private PacketHeadHat() { }

    /** Sets the hat every hatted entity shows, or removes it with {@code null}. */
    public static void setHat(ItemStack item) {
        hat = item == null || item.getType().isAir() ? null : NMSManager.getAdapter().nativeItemCopy(item);
    }

    public static synchronized void wear(int entityId) {
        int[] ids = hatted;
        int index = Arrays.binarySearch(ids, entityId);
        if (index >= 0) return;
        int insertion = -index - 1;
        int[] grown = new int[ids.length + 1];
        System.arraycopy(ids, 0, grown, 0, insertion);
        grown[insertion] = entityId;
        System.arraycopy(ids, insertion, grown, insertion + 1, ids.length - insertion);
        hatted = grown;
    }

    public static synchronized void unwear(int entityId) {
        int[] ids = hatted;
        int index = Arrays.binarySearch(ids, entityId);
        if (index < 0) return;
        int[] shrunk = new int[ids.length - 1];
        System.arraycopy(ids, 0, shrunk, 0, index);
        System.arraycopy(ids, index + 1, shrunk, index, ids.length - index - 1);
        hatted = shrunk;
    }

    /**
     * The hatted entity ids, sorted, possibly empty. The array is never changed once returned, so a packet hook can
     * check every packet of a bundle against one read; do not change it either.
     */
    public static int[] hatted() {
        return hatted;
    }

    /** Whether {@code entityId} is in ids returned by {@link #hatted()}. */
    public static boolean wears(int[] hatted, int entityId) {
        return hatted.length > 0 && Arrays.binarySearch(hatted, entityId) >= 0;
    }

    /** The hat as a native item stack, or null while none is set. Copy it before handing it to a packet. */
    public static Object hat() {
        return hat;
    }

    /** Keeps this viewer's equipment as the server sends it, for viewers who cannot draw the hat. */
    public static void skipViewer(UUID viewer) {
        SKIPPED_VIEWERS.add(viewer);
    }

    public static boolean skips(UUID viewer) {
        return SKIPPED_VIEWERS.contains(viewer);
    }

    public static void clearViewer(UUID viewer) {
        SKIPPED_VIEWERS.remove(viewer);
    }

    public static synchronized void clear() {
        hat = null;
        hatted = new int[0];
        SKIPPED_VIEWERS.clear();
    }
}
