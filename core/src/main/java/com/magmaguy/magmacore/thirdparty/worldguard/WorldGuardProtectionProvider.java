package com.magmaguy.magmacore.thirdparty.worldguard;

import com.magmaguy.magmacore.location.RegionProtectionProvider;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Returns true whenever the location is inside at least one non-global WorldGuard
 * region. Keeps the container reference cached to avoid repeated platform lookups.
 */
public class WorldGuardProtectionProvider implements RegionProtectionProvider {
    private final RegionContainer container;

    public WorldGuardProtectionProvider() {
        this.container = WorldGuard.getInstance().getPlatform().getRegionContainer();
    }

    @Override
    public boolean isProtected(Location location) {
        RegionQuery query = container.createQuery();
        ApplicableRegionSet set = query.getApplicableRegions(BukkitAdapter.adapt(location));
        return set.size() > 0;
    }

    @Override
    public boolean canBuild(Player player, Location location) {
        RegionQuery query = container.createQuery();
        var localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        var world = BukkitAdapter.adapt(location.getWorld());
        if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(localPlayer, world)) return true;
        return query.testBuild(BukkitAdapter.adapt(location), localPlayer);
    }

    @Override
    public String providerName() {
        return "WorldGuard";
    }
}
