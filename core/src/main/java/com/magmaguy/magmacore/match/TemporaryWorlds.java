package com.magmaguy.magmacore.match;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Worlds that exist only for one match. A match may add worlds while it runs. */
public final class TemporaryWorlds implements MatchSpace {
    private final List<World> worlds = new ArrayList<>();

    TemporaryWorlds(List<World> initial) {
        initial.forEach(this::addWorld);
    }

    public void addWorld(World world) {
        Objects.requireNonNull(world, "world");
        if (!worlds.contains(world)) worlds.add(world);
    }

    @Override
    public boolean contains(Location location) {
        return location != null && location.getWorld() != null && worlds.contains(location.getWorld());
    }

    @Override
    public Collection<World> worlds() {
        return List.copyOf(worlds);
    }

    @Override
    public boolean guardsEntryDuring(MatchPhase phase) {
        return phase != MatchPhase.DESTROYED;
    }

    @Override
    public void teardown() {
    }
}
