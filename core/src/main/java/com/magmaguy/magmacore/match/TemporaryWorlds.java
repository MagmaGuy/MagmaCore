package com.magmaguy.magmacore.match;

import com.magmaguy.magmacore.util.TemporaryWorldManager;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Worlds that exist only for one match. A match may add worlds while it runs. Teardown
 * deletes every one of them, and each carries a leftover marker in case the server crashes first.
 */
public final class TemporaryWorlds implements MatchSpace {
    private final List<World> worlds = new ArrayList<>();
    private final Consumer<World> deleter;

    TemporaryWorlds(List<World> initial) {
        this(initial, TemporaryWorldManager::permanentlyDeleteWorld);
    }

    TemporaryWorlds(List<World> initial, Consumer<World> deleter) {
        this.deleter = Objects.requireNonNull(deleter, "deleter");
        initial.forEach(this::addWorld);
    }

    public void addWorld(World world) {
        Objects.requireNonNull(world, "world");
        if (worlds.contains(world)) return;
        worlds.add(world);
        LeftoverWorlds.markLoaded(world);
    }

    @Override
    public boolean contains(Location location) {
        return location != null && location.getWorld() != null && worlds.contains(location.getWorld());
    }

    @Override
    public Collection<World> worlds() {
        return List.copyOf(worlds);
    }

    // Nobody but participants belongs in a temporary world, whatever the phase.
    @Override
    public boolean guardsEntryDuring(MatchPhase phase) {
        return phase != MatchPhase.DESTROYED;
    }

    @Override
    public void teardown() {
        for (World world : List.copyOf(worlds)) deleter.accept(world);
        worlds.clear();
    }
}
