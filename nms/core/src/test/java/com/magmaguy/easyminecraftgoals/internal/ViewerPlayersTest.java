package com.magmaguy.easyminecraftgoals.internal;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViewerPlayersTest {
    private final ViewerPlayers viewerPlayers = new ViewerPlayers();
    private final Map<UUID, Player> online = new HashMap<>();
    private final Set<UUID> viewers = new LinkedHashSet<>();

    @Test
    void unchangedViewersGetTheSameList() {
        Player first = join(), second = join();
        List<Player> players = resolve();
        assertEquals(List.of(first, second), players);
        assertSame(players, resolve());
    }

    @Test
    void offlineViewersAreLeftOutAsBefore() {
        Player first = join();
        Player second = join();
        List<Player> both = resolve();
        online.remove(uuidOf(second));
        List<Player> one = resolve();
        assertNotSame(both, one);
        assertEquals(List.of(first), one);
        assertEquals(List.of(first, second), both, "a list handed out never changes");
    }

    @Test
    void aViewerBackWithANewPlayerObjectGetsANewList() {
        Player first = join();
        UUID uuid = uuidOf(first);
        List<Player> before = resolve();
        Player rejoined = player(uuid);
        online.put(uuid, rejoined);
        List<Player> after = resolve();
        assertNotSame(before, after);
        assertSame(rejoined, after.get(0));
    }

    @Test
    void addedViewersAppearInViewerOrder() {
        Player first = join();
        resolve();
        Player second = join();
        assertEquals(List.of(first, second), resolve());
        viewers.clear();
        assertEquals(List.of(), resolve());
    }

    @Test
    void handedOutListsCannotBeChanged() {
        join();
        List<Player> players = resolve();
        assertThrows(UnsupportedOperationException.class, () -> players.add(null));
    }

    private List<Player> resolve() {
        return viewerPlayers.of(viewers, online::get);
    }

    private Player join() {
        UUID uuid = UUID.randomUUID();
        Player player = player(uuid);
        online.put(uuid, player);
        viewers.add(uuid);
        return player;
    }

    private UUID uuidOf(Player player) {
        return online.entrySet().stream().filter(entry -> entry.getValue() == player).findFirst().orElseThrow().getKey();
    }

    private static Player player(UUID uuid) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Player " + uuid;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
