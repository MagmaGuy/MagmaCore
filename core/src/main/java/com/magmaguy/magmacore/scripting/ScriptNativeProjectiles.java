package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.location.LocationQueryRegistry;
import com.magmaguy.magmacore.scripting.tables.LuaLivingEntityTable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Player;
import org.bukkit.damage.DamageSource;
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
    private static final ThreadLocal<Impact> CURRENT_IMPACT = new ThreadLocal<>();
    private static final class Impact {
        private final Projectile projectile;
        private final LivingEntity target;
        private final DamageSource source;
        private boolean applying;
        private boolean observed;

        private Impact(Projectile projectile, LivingEntity target, DamageSource source) {
            this.projectile = projectile;
            this.target = target;
            this.source = source;
        }
    }
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

    /** Retains the native cause/source only for the current impact's attributed application. */
    public static void applyAttributedDamage(LivingEntity target, double amount, Player actor) {
        Impact impact = CURRENT_IMPACT.get();
        if (impact == null || impact.applying || impact.target != target
                || impact.projectile.getShooter() != actor) {
            target.damage(amount, actor);
            return;
        }
        impact.applying = true;
        impact.observed = false;
        try {
            target.damage(amount, impact.source);
        } finally {
            impact.applying = false;
        }
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
        Impact current = CURRENT_IMPACT.get();
        if (current != null && current.applying && !current.observed
                && current.projectile == event.getDamager() && current.target == event.getEntity()) {
            // This is the one native application requested by the callback. Let every other
            // listener inspect its original projectile/explosion cause and cancel or scale it.
            current.observed = true;
            return;
        }
        boolean accepted = !event.isCancelled();
        event.setCancelled(true);
        if (!accepted || !binding.active.getAsBoolean() || !(event.getEntity() instanceof LivingEntity target)) return;
        double amount = event.getDamage();
        if (!Double.isFinite(amount) || amount <= 0) return;
        Impact impact = new Impact((Projectile) event.getDamager(), target, event.getDamageSource());
        // Reapply through Bukkit with the original damage source, preserving protection semantics.
        // Defer until the native event completes so its temporary damage/recursion context cannot leak.
        binding.instance.ownLater(1, () -> {
            if (!binding.active.getAsBoolean() || !target.isValid() || target.isDead()) return;
            Impact previous = CURRENT_IMPACT.get();
            CURRENT_IMPACT.set(impact);
            try {
                binding.instance.invokeOwnedCallback("native projectile damage", binding.damage,
                        LuaLivingEntityTable.build(target), LuaValue.valueOf(amount));
            } finally {
                if (previous == null) CURRENT_IMPACT.remove();
                else CURRENT_IMPACT.set(previous);
            }
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
