package com.magmaguy.magmacore.match;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Part of an existing world, such as an arena. The plugin resets it; teardown does nothing. */
final class RegionSpace implements MatchSpace {
    private final World world;
    private final Predicate<Location> contains;

    RegionSpace(World world, Predicate<Location> contains) {
        this.world = Objects.requireNonNull(world, "world");
        this.contains = Objects.requireNonNull(contains, "contains");
    }

    @Override
    public boolean contains(Location location) {
        return location != null && world.equals(location.getWorld()) && contains.test(location);
    }

    @Override
    public Collection<World> worlds() {
        return List.of(world);
    }

    // Warps into an idle arena must keep working, so the door only closes while it runs.
    @Override
    public boolean guardsEntryDuring(MatchPhase phase) {
        return phase == MatchPhase.STARTING || phase == MatchPhase.ONGOING;
    }

    @Override
    public void teardown() {
    }
}
