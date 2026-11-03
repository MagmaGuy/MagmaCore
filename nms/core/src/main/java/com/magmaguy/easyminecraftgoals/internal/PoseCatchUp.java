package com.magmaguy.easyminecraftgoals.internal;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * One packet entity's poses for players who skipped some.
 *
 * <p>A pose update carries only the entity-data values that changed since the last one, so a player who skipped an
 * update, because {@link PoseBacklog} found their connection behind, would never receive what changed in it. The
 * entity keeps the last value it sent for every entity-data slot and, for each player who skipped an update, the
 * slots that changed while they were behind. The next pose such a player gets carries the last value of those slots
 * and of the slots that update changes, which is everything they missed and nothing they already have. A pose
 * changes a few slots, so this is far smaller than every slot, and a link that is full carries more poses.</p>
 *
 * <p>Slots are bits of a {@code long}. Display entities use slots below 64; a slot beyond that counts as every slot,
 * so a player catching up then gets all of them.</p>
 *
 * <p>{@code V} is the version's entity-data value. Thread-safe: poses are built on the model clock, viewers are admitted
 * and dropped on the primary thread.</p>
 *
 * @param <V> the entity-data value type of the server version
 */
public final class PoseCatchUp<V> {

    /** Every slot: what a player owes when the update was a full snapshot, or touched a slot beyond the mask. */
    public static final long EVERY_SLOT = -1L;

    // Viewer to the slots that changed while they were behind; 0 when they missed only a move.
    private final Map<UUID, Long> owed = new ConcurrentHashMap<>();
    private final Map<Integer, V> lastSent = new LinkedHashMap<>();

    /** Viewers catching up on the same slots, who can share one packet. */
    public record CatchingUp(List<Player> viewers, long slots) {
    }

    /** The viewers of one pose update: those that get it, and those that get the slots they missed with it. */
    public record Recipients(List<Player> current, List<CatchingUp> catchingUp) {
        public boolean isEmpty() {
            return current.isEmpty() && catchingUp.isEmpty();
        }

        /** Both groups, for the parts of an update that carry the whole state anyway, such as the move. */
        public List<Player> all() {
            if (catchingUp.isEmpty()) return current;
            List<Player> all = new ArrayList<>(current);
            for (CatchingUp group : catchingUp) all.addAll(group.viewers());
            return all;
        }
    }

    /**
     * Remembers the values an update sends, by slot. Call it with every update the entity packs.
     *
     * @return the slots the update changed, as bits
     */
    public synchronized long record(List<V> values, ToIntFunction<V> slot) {
        if (values == null) return 0;
        long slots = 0;
        for (V value : values) {
            int id = slot.applyAsInt(value);
            lastSent.put(id, value);
            slots |= bit(id);
        }
        return slots;
    }

    /** The last value sent for each of the slots, in the order the slots were first sent. */
    public synchronized List<V> values(long slots) {
        if (slots == EVERY_SLOT) return new ArrayList<>(lastSent.values());
        List<V> values = new ArrayList<>(Long.bitCount(slots));
        if (slots == 0) return values;
        for (Map.Entry<Integer, V> entry : lastSent.entrySet())
            if ((slots & bit(entry.getKey())) != 0) values.add(entry.getValue());
        return values;
    }

    /**
     * Sorts the viewers of a pose update. A viewer whose connection is behind skips it and owes the slots it changes,
     * or just the update when it only moves. A viewer owed slots that is no longer behind catches up with this update,
     * grouped with those owed the same slots. The rest get it.
     *
     * @param changedSlots the slots the update changes, as {@link #record} returned them
     * @param moves        whether the update also moves the entity, so that skipping it misses something
     */
    public Recipients split(List<Player> viewers, long changedSlots, boolean moves) {
        List<Player> current = null;
        List<CatchingUp> catchingUp = null;
        for (int index = 0; index < viewers.size(); index++) {
            Player viewer = viewers.get(index);
            UUID id = viewer.getUniqueId();
            if (PoseBacklog.behind(id)) {
                if (changedSlots != 0 || moves) owed.merge(id, changedSlots, (earlier, later) -> earlier | later);
                // The first viewer left out needs its own list; until then the given one stands for the current.
                if (current == null) current = new ArrayList<>(viewers.subList(0, index));
                continue;
            }
            Long missed = owed.isEmpty() ? null : owed.remove(id);
            if (missed != null) {
                if (current == null) current = new ArrayList<>(viewers.subList(0, index));
                if (catchingUp == null) catchingUp = new ArrayList<>(1);
                join(catchingUp, viewer, missed | changedSlots);
                continue;
            }
            if (current != null) current.add(viewer);
        }
        return new Recipients(current == null ? viewers : current, catchingUp == null ? List.of() : catchingUp);
    }

    private static void join(List<CatchingUp> groups, Player viewer, long slots) {
        for (CatchingUp group : groups)
            if (group.slots() == slots) {
                group.viewers().add(viewer);
                return;
            }
        List<Player> viewers = new ArrayList<>(1);
        viewers.add(viewer);
        groups.add(new CatchingUp(viewers, slots));
    }

    /** Whether some viewer skipped an update and has not caught up yet. */
    public boolean owesAny() {
        return !owed.isEmpty();
    }

    /** Drops a viewer that left, or that just received the whole state as it was admitted. */
    public void forget(UUID viewer) {
        owed.remove(viewer);
    }

    private static long bit(int slot) {
        return slot >= 0 && slot < Long.SIZE ? 1L << slot : EVERY_SLOT;
    }
}
