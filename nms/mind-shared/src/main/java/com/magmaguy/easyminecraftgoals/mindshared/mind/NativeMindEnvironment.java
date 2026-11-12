package com.magmaguy.easyminecraftgoals.mindshared.mind;

import net.minecraft.world.entity.Mob;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * What a host hears from the server: intrinsic sunlight ignition, which it restricts on its carriers without
 * changing ordinary combat or environmental damage, and the moments a borrowed mob is about to be saved or copied,
 * when it has to have its own AI back (see {@link NativeMindBorrowedMarker}).
 */
final class NativeMindEnvironment implements Listener, AutoCloseable {
    private final NativeMindHost host;
    private final String hostIdentity;
    private final JavaPlugin plugin;

    NativeMindEnvironment(NativeMindHost host) {
        this.host = host;
        this.hostIdentity = host.hostIdentity();
        plugin = JavaPlugin.getProvidingPlugin(NativeMindHost.class);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // Mobs that loaded before this host existed, after a crash cut a borrow short.
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity living : world.getLivingEntities()) NativeMindBorrowedMarker.redeem(living);
        }
    }

    JavaPlugin plugin() {
        return plugin;
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity living) NativeMindBorrowedMarker.redeem(living);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) host.handBack(entity);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(EntityPortalEvent event) {
        host.handBack(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        host.borrowedTransformed(event.getEntity(), event.getTransformedEntities());
    }

    @Override public void close() { HandlerList.unregisterAll(this); }
}
