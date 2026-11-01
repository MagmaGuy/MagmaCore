package com.magmaguy.easyminecraftgoals.internal;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The online players among a packet entity's viewers, in viewer order. A packet entity asks for them with every
 * update it queues, and they rarely change, so the last list is handed out again while every viewer still resolves
 * to the same player object; any difference (a viewer added, removed, gone offline or back with a new player object)
 * builds a new list. Lists are never changed once handed out, so a queued bundle can keep one while the next update
 * runs on another thread.
 */
public final class ViewerPlayers {
    private volatile List<Player> last = List.of();

    /** The online players among {@code viewers}, as {@code Bukkit.getPlayer} resolves them; unmodifiable. */
    public List<Player> of(Iterable<UUID> viewers) {
        return of(viewers, Bukkit::getPlayer);
    }

    List<Player> of(Iterable<UUID> viewers, Function<UUID, Player> lookup) {
        List<Player> previous = last;
        int matched = 0;
        Iterator<UUID> iterator = viewers.iterator();
        while (iterator.hasNext()) {
            Player player = lookup.apply(iterator.next());
            if (player == null) continue;
            if (matched < previous.size() && previous.get(matched) == player) {
                matched++;
                continue;
            }
            List<Player> players = new ArrayList<>(Math.max(previous.size(), matched + 1));
            players.addAll(previous.subList(0, matched));
            players.add(player);
            while (iterator.hasNext()) {
                Player next = lookup.apply(iterator.next());
                if (next != null) players.add(next);
            }
            return publish(players);
        }
        if (matched == previous.size()) return previous;
        return publish(new ArrayList<>(previous.subList(0, matched)));
    }

    private List<Player> publish(List<Player> players) {
        List<Player> list = Collections.unmodifiableList(players);
        last = list;
        return list;
    }
}
