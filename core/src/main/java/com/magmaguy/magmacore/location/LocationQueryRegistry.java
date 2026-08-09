package com.magmaguy.magmacore.location;

import com.magmaguy.magmacore.location.api.LocationOwnership;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Static registry of {@link DungeonLocator}s and {@link RegionProtectionProvider}s.
 *
 * <p>Because Magmacore is shaded + relocated into some consumer plugins (e.g. FMM
 * relocates to {@code com.magmaguy.freeminecraftmodels.magmacore.*}) while others
 * ship it at its original package (EliteMobs), each consumer ends up with its own
 * isolated copy of this class. Dungeon providers must therefore push themselves
 * into each consumer's registry directly on plugin enable — EliteMobs registers
 * with its own copy here and also calls FMM's
 * {@code com.magmaguy.freeminecraftmodels.api.LocationAPI} so FMM's isolated
 * registry gets populated too.
 *
 * <p>Built-in region-protection adapters (WorldGuard, GriefPrevention) are
 * auto-registered on the first protection query. Protection check classes live
 * inside Magmacore itself, so they work uniformly across shaded and non-shaded
 * consumers — each consumer builds its own adapter using its own class copies.
 *
 * <p>Reads are lock-free via copy-on-write lists: registrations happen a handful
 * of times at plugin start-up and queries can occur many thousands of times per
 * second from scripts.
 */
public final class LocationQueryRegistry {
    private static final CopyOnWriteArrayList<DungeonLocator> dungeonLocators =
            new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<RegionProtectionProvider> protectionProviders =
            new CopyOnWriteArrayList<>();
    private static final Map<String, BuiltInRegistration> builtInProtectionProviders =
            new ConcurrentHashMap<>();
    private static final Map<String, Plugin> failedBuiltInAttempts =
            new ConcurrentHashMap<>();
    private static final AtomicBoolean warnedNoDungeonLocators = new AtomicBoolean(false);
    private static final AtomicBoolean warnedNoProtectionProviders = new AtomicBoolean(false);

    private LocationQueryRegistry() {
    }

    public static void registerDungeonLocator(DungeonLocator locator) {
        if (locator == null) return;
        dungeonLocators.addIfAbsent(locator);
    }

    public static void registerProtectionProvider(RegionProtectionProvider provider) {
        if (provider == null) return;
        protectionProviders.addIfAbsent(provider);
    }

    public static void unregisterDungeonLocator(DungeonLocator locator) {
        dungeonLocators.remove(locator);
    }

    public static void unregisterProtectionProvider(RegionProtectionProvider provider) {
        protectionProviders.remove(provider);
    }

    public static boolean isInAnyDungeon(Location location) {
        if (location == null || location.getWorld() == null) return false;

        // Local direct registrations (legacy; same-plugin only)
        for (DungeonLocator locator : dungeonLocators) {
            try {
                if (locator.contains(location)) return true;
            } catch (Throwable t) {
                Logger.warn("DungeonLocator threw during query: " + t.getMessage());
            }
        }

        // Cross-plugin discovery via Bukkit's ServicesManager. Ownership alone
        // is not enough: build zones and other plugin-owned regions are not
        // dungeons, so providers must explicitly advertise the dungeon kind.
        if (LocationOwnership.hasKind(location, "dungeon")) return true;

        if (dungeonLocators.isEmpty()
                && Bukkit.getServicesManager().getRegistrations(java.util.function.Predicate.class).isEmpty()
                && warnedNoDungeonLocators.compareAndSet(false, true)) {
            Logger.warn("is_in_dungeon called but no dungeon locators or LocationOwnership "
                    + "providers are registered. If EliteMobs is installed, ensure it is up to "
                    + "date and loaded before this query.");
        }
        return false;
    }

    public static boolean isInAnyProtectedRegion(Location location) {
        if (location == null || location.getWorld() == null) return false;
        ensureBuiltInProtectionProviders();

        // Local protection providers (WorldGuard/GriefPrevention adapters and any
        // plugin that called registerProtectionProvider on this same shaded copy).
        for (RegionProtectionProvider provider : protectionProviders) {
            try {
                if (provider.isProtected(location)) return true;
            } catch (Throwable t) {
                Logger.warn("RegionProtectionProvider '" + provider.providerName() + "' threw during query: " + t.getMessage());
                // This predicate is used to decide whether potentially destructive scripted
                // actions may proceed. An adapter failure must never be interpreted as an
                // unprotected location.
                return true;
            }
        }

        // Cross-plugin: LocationOwnership entries can opt-in to also report
        // protection via their own check (e.g. EM checks dungeon config).
        // We do NOT treat ownership as automatic protection — a plugin can
        // own a location without protecting it (sandbox dungeons, build zones).
        if (LocationOwnership.anyProtectedOwnerAt(location)) return true;

        if (protectionProviders.isEmpty()
                && Bukkit.getServicesManager().getRegistrations(java.util.function.Predicate.class).isEmpty()
                && warnedNoProtectionProviders.compareAndSet(false, true)) {
            Logger.warn("is_protected called but no protection providers or LocationOwnership "
                    + "owners are registered. Install WorldGuard / GriefPrevention or have a "
                    + "content plugin register via LocationOwnership for this check to work.");
        }
        return false;
    }

    /**
     * Asks each provider whether the player may build. No provider means no
     * extra restriction; adapter failures fail closed rather than bypassing it.
     */
    public static boolean canBuild(Player player, Location location) {
        if (player == null || location == null || location.getWorld() == null) return false;
        ensureBuiltInProtectionProviders();
        for (RegionProtectionProvider provider : protectionProviders) {
            try {
                if (!provider.canBuild(player, location)) return false;
            } catch (Throwable t) {
                Logger.warn("RegionProtectionProvider '" + provider.providerName()
                        + "' threw during player build query: " + t.getMessage());
                return false;
            }
        }
        // Ownership providers currently expose only a global protection predicate.
        return !LocationOwnership.anyProtectedOwnerAt(location);
    }

    public static int getDungeonLocatorCount() {
        return dungeonLocators.size();
    }

    public static int getProtectionProviderCount() {
        ensureBuiltInProtectionProviders();
        return protectionProviders.size();
    }

    /**
     * Force the built-in protection providers to attempt registration now rather than
     * on first query. Consumer plugins call this on enable so that WorldGuard /
     * GriefPrevention attach attempts are visible in startup logs, not deferred until
     * a Lua script first hits {@code is_protected}.
     */
    public static void initializeBuiltInProtectionProviders() {
        ensureBuiltInProtectionProviders();
        if (protectionProviders.isEmpty()) {
            boolean wgInstalled = isPluginEnabled("WorldGuard");
            boolean gpInstalled = isPluginEnabled("GriefPrevention");
            if (!wgInstalled && !gpInstalled) {
                Logger.warn("No protection providers registered — WorldGuard and GriefPrevention "
                        + "are both absent. em.location.is_protected() will always return false.");
            } else {
                Logger.warn("No protection providers registered despite "
                        + (wgInstalled ? "WorldGuard" : "")
                        + (wgInstalled && gpInstalled ? " and " : "")
                        + (gpInstalled ? "GriefPrevention" : "")
                        + " being installed — check earlier 'Failed to attach ... provider' messages "
                        + "for the underlying class-load failure.");
            }
        }
    }

    public static void shutdown() {
        dungeonLocators.clear();
        protectionProviders.clear();
        builtInProtectionProviders.clear();
        failedBuiltInAttempts.clear();
        warnedNoDungeonLocators.set(false);
        warnedNoProtectionProviders.set(false);
    }

    private static void ensureBuiltInProtectionProviders() {
        refreshBuiltInProtection(
                "WorldGuard",
                "com.magmaguy.magmacore.thirdparty.worldguard.WorldGuardProtectionProvider");
        refreshBuiltInProtection(
                "GriefPrevention",
                "com.magmaguy.magmacore.thirdparty.griefprevention.GriefPreventionProtectionProvider");
    }

    private static void refreshBuiltInProtection(
            String pluginName, String providerClassName) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        BuiltInRegistration existing =
                builtInProtectionProviders.get(pluginName);
        if (plugin != null && plugin.isEnabled() &&
                existing != null && existing.plugin() == plugin) return;
        if (plugin != null && plugin.isEnabled() &&
                failedBuiltInAttempts.get(pluginName) == plugin) return;

        // Registration and provider-list mutation must be one operation. The fast
        // stable-provider path above stays lock-free, while concurrent first queries
        // cannot install duplicate adapters or race a plugin-disable removal.
        synchronized (builtInProtectionProviders) {
            plugin = Bukkit.getPluginManager().getPlugin(pluginName);
            existing = builtInProtectionProviders.get(pluginName);
            if (plugin == null || !plugin.isEnabled()) {
                if (existing != null &&
                        builtInProtectionProviders.remove(
                                pluginName,
                                existing)) {
                    protectionProviders.remove(existing.provider());
                }
                failedBuiltInAttempts.remove(pluginName);
                return;
            }
            if (existing != null && existing.plugin() == plugin) return;
            if (failedBuiltInAttempts.get(pluginName) == plugin) return;

            if (existing != null &&
                    builtInProtectionProviders.remove(
                            pluginName,
                            existing)) {
                protectionProviders.remove(existing.provider());
            }
            try {
                Class<?> cls = Class.forName(
                        providerClassName,
                        true,
                        LocationQueryRegistry.class.getClassLoader());
                Object instance = cls.getDeclaredConstructor().newInstance();
                if (instance instanceof RegionProtectionProvider provider) {
                    BuiltInRegistration registration =
                            new BuiltInRegistration(plugin, provider);
                    builtInProtectionProviders.put(
                            pluginName,
                            registration);
                    protectionProviders.addIfAbsent(provider);
                    failedBuiltInAttempts.remove(pluginName);
                    Logger.info("Registered " + provider.providerName()
                            + " protection provider.");
                }
            } catch (LinkageError | ReflectiveOperationException |
                     RuntimeException ex) {
                failedBuiltInAttempts.put(pluginName, plugin);
                Logger.warn("Failed to attach " + pluginName
                        + " protection provider: " + ex.getMessage());
            }
        }
    }

    private static boolean isPluginEnabled(String pluginName) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        return plugin != null && plugin.isEnabled();
    }

    private record BuiltInRegistration(
            Plugin plugin, RegionProtectionProvider provider) {
    }
}
