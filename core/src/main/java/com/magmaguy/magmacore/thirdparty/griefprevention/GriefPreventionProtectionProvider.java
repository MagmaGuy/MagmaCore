package com.magmaguy.magmacore.thirdparty.griefprevention;

import com.magmaguy.magmacore.location.RegionProtectionProvider;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * Reflection-backed adapter for GriefPrevention. Calls
 * {@code GriefPrevention.instance.dataStore.getClaimAt(location, true, null)}
 * and treats a non-null return as "protected".
 *
 * <p>MethodHandles are resolved once in the constructor and cached, so hot-path
 * queries avoid {@link java.lang.reflect.Method} lookup overhead. Uses reflection
 * rather than a typed compileOnly dependency so Magmacore doesn't need to pin a
 * specific GriefPrevention version or add a new maven repo.
 */
public class GriefPreventionProtectionProvider implements RegionProtectionProvider {
    private final Object dataStore;
    private final MethodHandle getClaimAt;
    private final MethodHandle allowBuild;

    public GriefPreventionProtectionProvider() throws ReflectiveOperationException {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        if (plugin == null) throw new IllegalStateException("GriefPrevention is not loaded");
        Field dataStoreField = plugin.getClass().getField("dataStore");
        this.dataStore = dataStoreField.get(plugin);
        if (this.dataStore == null) throw new IllegalStateException("GriefPrevention dataStore is null");
        Class<?> claimClass = Class.forName("me.ryanhamshire.GriefPrevention.Claim");
        MethodType type = MethodType.methodType(
                claimClass, Location.class, boolean.class, claimClass);
        this.getClaimAt = MethodHandles.lookup()
                .findVirtual(dataStore.getClass(), "getClaimAt", type);
        this.allowBuild = MethodHandles.lookup().findVirtual(
                claimClass, "allowBuild",
                MethodType.methodType(String.class, Player.class, Material.class));
    }

    @Override
    public boolean isProtected(Location location) {
        try {
            Object claim = getClaimAt.invoke(dataStore, location, true, null);
            return claim != null;
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "GriefPrevention claim lookup failed", t);
        }
    }

    @Override
    public boolean canBuild(Player player, Location location) {
        try {
            Object claim = getClaimAt.invoke(dataStore, location, true, null);
            return claim == null || allowBuild.invoke(claim, player, Material.STONE) == null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String providerName() {
        return "GriefPrevention";
    }
}
