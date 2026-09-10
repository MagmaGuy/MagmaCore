package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.projectiles.MagicProjectileEngine;
import com.magmaguy.magmacore.scripting.tables.LuaLivingEntityTable;
import com.magmaguy.magmacore.scripting.tables.LuaTableSupport;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaValue;
import java.util.function.BooleanSupplier;

/** Lua callbacks on the canonical physical projectile engine, owned by the ordinary script instance. */
public final class ScriptProjectiles implements AutoCloseable {
    private record Flight(Player owner, MagicProjectileEngine.Traits traits, ScriptInstance instance,
                          BooleanSupplier alive, LuaFunction impact, LuaFunction visual, boolean protectedPath)
            implements MagicProjectileEngine.Source {
        @Override public boolean active() { return alive.getAsBoolean(); }
    }
    private record Traits(double projectileSpeed, double range, int travelTicks) implements MagicProjectileEngine.Traits {
        @Override public int missileCount() { return 1; }
        @Override public double spreadDegrees() { return 0; }
    }
    private final MagicProjectileEngine<Flight> engine;

    public ScriptProjectiles(Plugin plugin) {
        engine = new MagicProjectileEngine<>(plugin, (flight, target, at, incoming) -> {
            if (flight.active()) flight.instance.invokeOwnedCallback("projectile impact", flight.impact,
                    target == null ? LuaValue.NIL : LuaLivingEntityTable.build(target), LuaTableSupport.locationToTable(at));
        }) {
            @Override protected void renderStraightFlight(Flight flight, Location location) {
                if (flight.visual != null) flight.instance.invokeOwnedCallback("projectile visual", flight.visual,
                        LuaTableSupport.locationToTable(location));
            }
            @Override protected boolean isAbsorbed(Flight flight, Location from, Location to) {
                if (!flight.protectedPath) return false;
                double distance = from.distance(to);
                int steps = Math.max(1, (int) Math.ceil(distance * 4));
                Vector step = to.toVector().subtract(from.toVector()).multiply(1D / steps);
                for (int i = 0; i <= steps; i++) {
                    Location at = from.clone().add(step.clone().multiply(i));
                    if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)
                            || com.magmaguy.magmacore.location.LocationQueryRegistry.isInAnyProtectedRegion(at)) return true;
                }
                return false;
            }
        };
        engine.start();
    }

    public boolean launch(ScriptInstance instance, Player owner, BooleanSupplier active, Location at, Vector direction,
                          double speed, double range, int ticks, LuaFunction impact, LuaFunction visual, boolean protectedPath) {
        if (!Double.isFinite(speed) || speed <= 0 || !Double.isFinite(range) || range <= 0 || ticks < 1)
            throw new IllegalArgumentException("Invalid projectile flight parameters");
        var flight = new Flight(owner, new Traits(speed, range, ticks), instance, active, impact, visual, protectedPath);
        if (!engine.launchStraight(flight, at, direction)) return false;
        instance.ownCleanup(() -> engine.cancel(flight));
        return true;
    }

    @Override public void close() { engine.close(); }
}
