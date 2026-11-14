package com.magmaguy.magmacore.enchantments;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Collection;
import java.util.function.BiFunction;

/**
 * Remembers {@link EnchantmentInputs#elected(Plugin, String)} for one host while the provider services it
 * reads stay the same. The election depends only on which BiFunction services are registered, in which
 * order, by which plugins, and whether those plugins are enabled: an endpoint's namespace, plugin and
 * capabilities never change, and an endpoint only goes inactive as it leaves the services list. Each call
 * still reads the live services list and every owner's enabled flag, so a peer that registers, unregisters,
 * hot swaps or disables in any shaded copy is seen on the very next call without listening for events.
 */
final class ProviderElection {
    private final Plugin plugin;
    private final String namespace;
    private Object[] services;
    private Plugin[] owners;
    private boolean[] enabled;
    private boolean result;

    ProviderElection(Plugin plugin, String namespace) {
        this.plugin = plugin;
        this.namespace = namespace;
    }

    @SuppressWarnings("rawtypes")
    boolean elected() {
        EnchantmentProviders.requireServerThread();
        Collection<RegisteredServiceProvider<BiFunction>> registrations =
                Bukkit.getServicesManager().getRegistrations(BiFunction.class);
        if (unchanged(registrations)) return result;
        services = null;
        boolean computed = EnchantmentInputs.elected(plugin, namespace);
        int size = registrations.size();
        Object[] nextServices = new Object[size];
        Plugin[] nextOwners = new Plugin[size];
        boolean[] nextEnabled = new boolean[size];
        int index = 0;
        for (RegisteredServiceProvider<BiFunction> registration : registrations) {
            nextServices[index] = registration.getProvider();
            nextOwners[index] = registration.getPlugin();
            nextEnabled[index] = registration.getPlugin().isEnabled();
            index++;
        }
        result = computed;
        owners = nextOwners;
        enabled = nextEnabled;
        services = nextServices;
        return computed;
    }

    @SuppressWarnings("rawtypes")
    private boolean unchanged(Collection<RegisteredServiceProvider<BiFunction>> registrations) {
        if (services == null || services.length != registrations.size()) return false;
        int index = 0;
        for (RegisteredServiceProvider<BiFunction> registration : registrations) {
            if (registration.getProvider() != services[index] || registration.getPlugin() != owners[index]
                    || registration.getPlugin().isEnabled() != enabled[index]) return false;
            index++;
        }
        return true;
    }
}
