package com.magmaguy.magmacore.location.api;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Cross-plugin location ownership API.
 *
 * <p>Plugins that "own" certain locations (worlds, regions, instanced content,
 * scripted areas) register themselves once on enable. Any other plugin can
 * then ask whether a given location is owned, by whom, and with what kind
 * tags — without taking a hard dependency on the owning plugin.
 *
 * <p>Backed by Bukkit's {@code ServicesManager}, so registrations propagate
 * across plugin classloaders even when MagmaCore is shaded into multiple
 * consumer plugins. Registrations are keyed under {@link Predicate} (a JDK
 * class with stable identity across classloaders); each plugin's shaded copy
 * of {@link LocationOwnershipEntry} is identified by its simple class name —
 * which the shade relocation does not rename — and dispatched via reflection
 * on JDK-stable method signatures.
 *
 * <p>Bukkit auto-unregisters all of a plugin's services when the plugin
 * disables, so callers don't need to clean up.
 */
public final class LocationOwnership {

    /** Cached reflective methods, keyed by the entry's Class. */
    private static final java.util.Map<Class<?>, EntryMethods> methodCache = new ConcurrentHashMap<>();

    private LocationOwnership() {
    }

    /**
     * Register a plugin as owning certain locations.
     *
     * @param plugin       the registering plugin (Bukkit clears the registration on disable)
     * @param namespace    short stable identifier (e.g. {@code "EliteMobs"})
     * @param ownsLocation returns true when the location is owned by this namespace
     */
    public static void register(Plugin plugin, String namespace, Predicate<Location> ownsLocation) {
        register(plugin, new LocationOwnershipEntry(namespace, ownsLocation, null, null));
    }

    /**
     * Register a plugin as owning certain locations, with an explicit protection check.
     * Use this overload when ownership and protection are not the same — for example
     * EliteMobs owns every dungeon world but only some are configured as protected.
     *
     * @param protectionFn returns true if the owner is also protecting the location
     *                     (writes/grief should be denied). Independent of ownership.
     */
    public static void register(Plugin plugin, String namespace,
                                Predicate<Location> ownsLocation,
                                Predicate<Location> protectionFn) {
        register(plugin, new LocationOwnershipEntry(namespace, ownsLocation, null, protectionFn));
    }

    /**
     * Register a plugin as owning certain locations with kind metadata.
     *
     * @param kindsFn returns the set of kind tags that apply at the location
     *                (e.g. {@code Set.of("dungeon", "instanced")}). A non-empty
     *                return implies ownership; an empty or null return means
     *                "not owned".
     */
    public static void registerTyped(Plugin plugin, String namespace, Function<Location, Set<String>> kindsFn) {
        registerTyped(plugin, namespace, kindsFn, null);
    }

    /**
     * Typed registration with an explicit protection check (see
     * {@link #register(Plugin, String, Predicate, Predicate)}).
     */
    public static void registerTyped(Plugin plugin, String namespace,
                                     Function<Location, Set<String>> kindsFn,
                                     Predicate<Location> protectionFn) {
        Predicate<Location> ownsFn = loc -> {
            Set<String> k = kindsFn.apply(loc);
            return k != null && !k.isEmpty();
        };
        register(plugin, new LocationOwnershipEntry(namespace, ownsFn, kindsFn, protectionFn));
    }

    /**
     * Lower-level escape hatch — register a pre-built entry directly. Useful
     * when ownership and kind queries should not run the same underlying logic
     * twice.
     */
    public static void register(Plugin plugin, LocationOwnershipEntry entry) {
        Bukkit.getServicesManager().register(Predicate.class, entry, plugin, ServicePriority.Normal);
        Logger.info("Registered location ownership provider: " + entry.getNamespace()
                + " (plugin=" + plugin.getName() + ")");
    }

    /** All namespaces that own this location, across every consumer plugin. */
    public static Set<String> ownersAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return Collections.emptySet();
        Set<String> owners = new HashSet<>();
        for (Object provider : ourProviders()) {
            EntryMethods m = methodsFor(provider.getClass());
            if (m == null) continue;
            try {
                if ((boolean) m.test.invoke(provider, loc)) {
                    owners.add((String) m.getNamespace.invoke(provider));
                }
            } catch (ReflectiveOperationException e) {
                Logger.warn("LocationOwnership.ownersAt dispatch failed: " + e.getMessage());
            }
        }
        return owners;
    }

    /** True if {@code namespace} owns the given location. */
    public static boolean ownedBy(Location loc, String namespace) {
        if (loc == null || loc.getWorld() == null || namespace == null) return false;
        for (Object provider : ourProviders()) {
            EntryMethods m = methodsFor(provider.getClass());
            if (m == null) continue;
            try {
                if (!namespace.equals(m.getNamespace.invoke(provider))) continue;
                if ((boolean) m.test.invoke(provider, loc)) return true;
            } catch (ReflectiveOperationException e) {
                Logger.warn("LocationOwnership.ownedBy dispatch failed: " + e.getMessage());
            }
        }
        return false;
    }

    /** Aggregate kind tags at {@code loc} from every owner that claims it. */
    public static Set<String> kindsAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return Collections.emptySet();
        Set<String> result = new HashSet<>();
        for (Object provider : ourProviders()) {
            EntryMethods m = methodsFor(provider.getClass());
            if (m == null) continue;
            try {
                @SuppressWarnings("unchecked")
                Set<String> kinds = (Set<String>) m.kindsAt.invoke(provider, loc);
                if (kinds != null) result.addAll(kinds);
            } catch (ReflectiveOperationException e) {
                Logger.warn("LocationOwnership.kindsAt dispatch failed: " + e.getMessage());
            }
        }
        return result;
    }

    /** True if any registered owner reports {@code kind} at {@code loc}. */
    public static boolean hasKind(Location loc, String kind) {
        if (kind == null) return false;
        return kindsAt(loc).contains(kind);
    }

    /** True if at least one owner claims this location. */
    public static boolean anyOwnerAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        for (Object provider : ourProviders()) {
            EntryMethods m = methodsFor(provider.getClass());
            if (m == null) continue;
            try {
                if ((boolean) m.test.invoke(provider, loc)) return true;
            } catch (ReflectiveOperationException e) {
                Logger.warn("LocationOwnership.anyOwnerAt dispatch failed: " + e.getMessage());
            }
        }
        return false;
    }

    /** True if at least one owner protects this location (write/grief restrictions apply). */
    public static boolean anyProtectedOwnerAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        for (Object provider : ourProviders()) {
            EntryMethods m = methodsFor(provider.getClass());
            if (m == null || m.isProtectedAt == null) continue;
            try {
                if ((boolean) m.isProtectedAt.invoke(provider, loc)) return true;
            } catch (ReflectiveOperationException e) {
                Logger.warn("LocationOwnership.anyProtectedOwnerAt dispatch failed: " + e.getMessage());
            }
        }
        return false;
    }

    private static Collection<Object> ourProviders() {
        Collection<RegisteredServiceProvider<Predicate>> regs =
                Bukkit.getServicesManager().getRegistrations(Predicate.class);
        Set<Object> ours = new HashSet<>();
        for (RegisteredServiceProvider<Predicate> rsp : regs) {
            Object p = rsp.getProvider();
            if (p == null) continue;
            // Identify our entries by simple class name — preserved through shade
            // relocation, so EliteMobs's shaded copy and FMM's shaded copy both
            // qualify even though they're different Class<?> instances.
            if ("LocationOwnershipEntry".equals(p.getClass().getSimpleName())) {
                ours.add(p);
            }
        }
        return ours;
    }

    private static EntryMethods methodsFor(Class<?> cls) {
        EntryMethods cached = methodCache.get(cls);
        if (cached != null) return cached;
        try {
            // isProtectedAt is optional — older shaded copies (from a plugin
            // built against a pre-protection MagmaCore) won't have it.
            Method protectedM = null;
            try {
                protectedM = cls.getMethod("isProtectedAt", Location.class);
            } catch (NoSuchMethodException ignored) {
            }
            EntryMethods m = new EntryMethods(
                    cls.getMethod("getNamespace"),
                    cls.getMethod("test", Object.class),
                    cls.getMethod("kindsAt", Location.class),
                    protectedM);
            methodCache.put(cls, m);
            return m;
        } catch (NoSuchMethodException e) {
            Logger.warn("LocationOwnership: registration with unexpected shape "
                    + cls.getName() + " — missing " + e.getMessage());
            return null;
        }
    }

    private record EntryMethods(Method getNamespace, Method test, Method kindsAt, Method isProtectedAt) {
    }
}
