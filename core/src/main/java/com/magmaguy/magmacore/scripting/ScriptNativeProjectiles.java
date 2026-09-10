package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.location.LocationQueryRegistry;
import com.magmaguy.magmacore.scripting.tables.LuaLivingEntityTable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.plugin.Plugin;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaValue;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Optional attribution/restoration adapter for native projectiles spawned by ordinary Lua. */
public final class ScriptNativeProjectiles implements Listener, AutoCloseable {
    private static final String PROTECTED = "nightbreak_script_projectile_protection";
    private record Binding(ScriptInstance instance, BooleanSupplier active, LuaFunction damage, boolean protect) { }
    private final Map<UUID, Binding> bindings = new HashMap<>();
    private final Plugin plugin;

    public ScriptNativeProjectiles(Plugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Stable metadata allows each shaded host's existing explosion policy to recognize opt-in projectiles. */
    public static boolean isProtectionAware(org.bukkit.entity.Entity entity) {
        return entity != null && entity.hasMetadata(PROTECTED);
    }

    public boolean bind(Projectile projectile, ScriptInstance instance, BooleanSupplier active, LuaFunction damage, boolean protect) {
        if (!projectile.isValid() || bindings.containsKey(projectile.getUniqueId())) return false;
        var binding = new Binding(instance, active, damage, protect);
        bindings.put(projectile.getUniqueId(), binding);
        if (protect) projectile.setMetadata(PROTECTED, new org.bukkit.metadata.FixedMetadataValue(plugin, true));
        instance.ownCleanup(() -> {
            bindings.remove(projectile.getUniqueId(), binding);
            if (protect) projectile.removeMetadata(PROTECTED, plugin);
        });
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void damage(EntityDamageByEntityEvent event) {
        Binding binding = bindings.get(event.getDamager().getUniqueId());
        if (binding == null) return;
        boolean accepted = !event.isCancelled();
        event.setCancelled(true);
        if (!accepted || !binding.active.getAsBoolean() || !(event.getEntity() instanceof LivingEntity target)) return;
        double amount = event.getDamage();
        if (!Double.isFinite(amount) || amount <= 0) return;
        // The callback applies a new, attributed normal damage event; that event must pass protections.
        // Defer until the native event completes so its temporary damage/recursion context cannot leak.
        binding.instance.ownLater(1, () -> {
            if (binding.active.getAsBoolean() && target.isValid() && !target.isDead())
                binding.instance.invokeOwnedCallback("native projectile damage", binding.damage,
                        LuaLivingEntityTable.build(target), LuaValue.valueOf(amount));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explode(EntityExplodeEvent event) {
        Binding binding = bindings.get(event.getEntity().getUniqueId());
        if (binding == null || !binding.protect) return;
        if (!binding.active.getAsBoolean() || LocationQueryRegistry.isInAnyProtectedRegion(event.getLocation())) {
            event.setCancelled(true);
            return;
        }
        event.blockList().removeIf(block -> LocationQueryRegistry.isInAnyProtectedRegion(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void ignite(BlockIgniteEvent event) {
        if (event.getIgnitingEntity() == null) return;
        Binding binding = bindings.get(event.getIgnitingEntity().getUniqueId());
        if (binding != null && binding.protect && (!binding.active.getAsBoolean()
                || LocationQueryRegistry.isInAnyProtectedRegion(event.getBlock().getLocation()))) event.setCancelled(true);
    }

    @Override public void close() { HandlerList.unregisterAll(this); bindings.clear(); }
}
