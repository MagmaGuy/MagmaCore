package com.magmaguy.magmacore.location.api;

import org.bukkit.Location;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Marker class registered with Bukkit's {@code ServicesManager} under
 * {@link Predicate}{@code <Location>}. Carries the namespace, ownership /
 * kind resolvers, and an optional protection check for one plugin's
 * location-ownership claim.
 *
 * <p>Cross-plugin dispatch trick: this class is shaded into each consumer
 * plugin separately, so registrations from EliteMobs's copy and FMM's copy
 * have different {@code Class<?>} identities. {@link LocationOwnership}'s
 * lookup identifies "our" entries by simple class name (preserved through
 * shade relocation) and invokes them via reflection on the JDK-stable method
 * signatures {@link #getNamespace()}, {@link #test(Location)},
 * {@link #kindsAt(Location)} and {@link #isProtectedAt(Location)}.
 */
public final class LocationOwnershipEntry implements Predicate<Location> {

    private final String namespace;
    private final Predicate<Location> ownsFn;
    private final Function<Location, Set<String>> kindsFn;
    private final Predicate<Location> protectionFn;

    public LocationOwnershipEntry(String namespace,
                                  Predicate<Location> ownsFn,
                                  Function<Location, Set<String>> kindsFn,
                                  Predicate<Location> protectionFn) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.ownsFn = Objects.requireNonNull(ownsFn, "ownsFn");
        this.kindsFn = kindsFn != null ? kindsFn : (loc -> Collections.emptySet());
        // Default: ownership does NOT imply protection — owners must opt in.
        // Plugins with managed worlds where some areas are unprotected (e.g.
        // sandbox dungeons in EliteMobs) can return false here even when
        // ownsFn returns true.
        this.protectionFn = protectionFn != null ? protectionFn : (loc -> false);
    }

    public String getNamespace() {
        return namespace;
    }

    public Set<String> kindsAt(Location loc) {
        Set<String> result = kindsFn.apply(loc);
        return result == null ? Collections.emptySet() : result;
    }

    /** True if this owner protects the given location (write/grief restrictions apply). */
    public boolean isProtectedAt(Location loc) {
        return protectionFn.test(loc);
    }

    @Override
    public boolean test(Location loc) {
        return ownsFn.test(loc);
    }
}
