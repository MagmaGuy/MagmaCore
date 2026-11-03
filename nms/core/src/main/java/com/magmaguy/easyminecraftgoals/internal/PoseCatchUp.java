package com.magmaguy.easyminecraftgoals.internal;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * One packet entity's poses for players who skipped some.
 *
 * <p>A pose update carries only the entity-data values that changed since the last one, so a player who skipped an
 * update, because {@link PoseBacklog} found their connection behind, would never receive what changed in it. The
 * entity keeps the last value it sent for every entity-data slot, and the players who skipped an update, and each of
 * them receives all those values with the next pose it gets. That is every value it may have missed, and no more than
 * one of each.</p>
 *
 * <p>{@code V} is the version's entity-data value. Thread-safe: poses are built on the model clock, viewers are admitted
 * and dropped on the primary thread.</p>
 *
 * @param <V> the entity-data value type of the server version
 */
public final class PoseCatchUp<V> {

    private final Set<UUID> owed = ConcurrentHashMap.newKeySet();
    private final Map<Integer, V> lastSent = new LinkedHashMap<>();

    /** The viewers of one pose update: those that get it, and those that get every value instead. */
    public record Recipients(List<Player> current, List<Player> catchingUp) {
        public boolean isEmpty() {
            return current.isEmpty() && catchingUp.isEmpty();
        }

        /** Both groups, for the parts of an update that carry the whole state anyway. */
        public List<Player> all() {
            if (catchingUp.isEmpty()) return current;
            if (current.isEmpty()) return catchingUp;
            List<Player> all = new ArrayList<>(current.size() + catchingUp.size());
            all.addAll(current);
            all.addAll(catchingUp);
            return all;
        }
    }

    /** Remembers the values an update sends, by slot. Call it with every update the entity packs. */
    public synchronized void record(List<V> values, ToIntFunction<V> slot) {
        if (values == null) return;
        for (V value : values) lastSent.put(slot.applyAsInt(value), value);
    }

    /** The last value sent for every slot, for a player catching up. */
    public synchronized List<V> everything() {
        return new ArrayList<>(lastSent.values());
    }

    /**
     * Sorts the viewers of a pose update. A viewer whose connection is behind skips it, and is owed the values if the
     * update changes any. A viewer owed values that is no longer behind catches up with this update. The rest get it.
     *
     * @param changes whether the update changes anything, so that skipping it misses something
     */
    public Recipients split(List<Player> viewers, boolean changes) {
        List<Player> current = null, catchingUp = null;
        for (int index = 0; index < viewers.size(); index++) {
            Player viewer = viewers.get(index);
            UUID id = viewer.getUniqueId();
            if (PoseBacklog.behind(id)) {
                if (changes) owed.add(id);
                // The first viewer left out needs its own list; until then the given one stands for the current.
                if (current == null) current = new ArrayList<>(viewers.subList(0, index));
                continue;
            }
            if (!owed.isEmpty() && owed.remove(id)) {
                if (catchingUp == null) catchingUp = new ArrayList<>();
                if (current == null) current = new ArrayList<>(viewers.subList(0, index));
                catchingUp.add(viewer);
                continue;
            }
            if (current != null) current.add(viewer);
        }
        return new Recipients(current == null ? viewers : current, catchingUp == null ? List.of() : catchingUp);
    }

    /** Whether some viewer skipped an update and has not caught up yet. */
    public boolean owesAny() {
        return !owed.isEmpty();
    }

    /** Drops a viewer that left, or that just received the whole state as it was admitted. */
    public void forget(UUID viewer) {
        owed.remove(viewer);
    }
}
