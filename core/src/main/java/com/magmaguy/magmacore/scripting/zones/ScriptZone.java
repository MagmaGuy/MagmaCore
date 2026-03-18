package com.magmaguy.magmacore.scripting.zones;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.BiConsumer;

public class ScriptZone {
    private final Shape shape;
    private final Set<UUID> insidePlayers = new HashSet<>();
    private BiConsumer<Player, ScriptZone> onEnter;
    private BiConsumer<Player, ScriptZone> onLeave;

    public ScriptZone(Shape shape) {
        this.shape = shape;
    }

    public void setOnEnter(BiConsumer<Player, ScriptZone> callback) { this.onEnter = callback; }
    public void setOnLeave(BiConsumer<Player, ScriptZone> callback) { this.onLeave = callback; }
    public Shape getShape() { return shape; }

    public void tick(Collection<? extends Player> nearbyPlayers) {
        Set<UUID> currentlyInside = new HashSet<>();
        for (Player player : nearbyPlayers) {
            if (shape.contains(player.getLocation())) {
                currentlyInside.add(player.getUniqueId());
                if (!insidePlayers.contains(player.getUniqueId()) && onEnter != null) {
                    onEnter.accept(player, this);
                }
            }
        }
        for (UUID uuid : insidePlayers) {
            if (!currentlyInside.contains(uuid) && onLeave != null) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) onLeave.accept(player, this);
            }
        }
        insidePlayers.clear();
        insidePlayers.addAll(currentlyInside);
    }

    public void shutdown() {
        insidePlayers.clear();
    }
}
