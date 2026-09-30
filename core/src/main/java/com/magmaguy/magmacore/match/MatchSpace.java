package com.magmaguy.magmacore.match;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/** Where a match takes place. */
public interface MatchSpace {
    static MatchSpace region(World world, Predicate<Location> contains) {
        return new RegionSpace(world, contains);
    }

    static MatchSpace region(World world, BoundingBox box) {
        BoundingBox copy = box.clone();
        return new RegionSpace(world, location -> copy.contains(location.toVector()));
    }

    static TemporaryWorlds temporaryWorlds(World... worlds) {
        return new TemporaryWorlds(List.of(worlds));
    }

    boolean contains(Location location);

    Collection<World> worlds();

    /** Whether non-participants are refused teleports into this space during the phase. */
    boolean guardsEntryDuring(MatchPhase phase);

    void teardown();
}
