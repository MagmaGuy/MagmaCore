package com.magmaguy.easyminecraftgoals.mindshared.mind;

import net.minecraft.world.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;

/** Restricts intrinsic sunlight ignition without changing ordinary combat or environmental damage. */
final class NativeMindEnvironment implements Listener, AutoCloseable {
    private final String hostIdentity;

    NativeMindEnvironment(String hostIdentity) {
        this.hostIdentity = hostIdentity;
        JavaPlugin plugin = JavaPlugin.getProvidingPlugin(NativeMindHost.class);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        // Fire attacks and lava/fire blocks carry their native entity/block cause.
        if (event.getClass() != EntityCombustEvent.class) return;
        if (!(event.getEntity() instanceof LivingEntity living)) return;
        if (!MindCarrierState.isMarked(living, hostIdentity)) return;
        if (NativeMindVersion.getNMSLivingEntity(living) instanceof Mob mob
                && NativeMindVersion.sunlightExposure(mob)) event.setCancelled(true);
    }

    @Override public void close() { HandlerList.unregisterAll(this); }
}
