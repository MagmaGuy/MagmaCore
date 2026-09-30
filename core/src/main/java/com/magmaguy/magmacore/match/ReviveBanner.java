package com.magmaguy.magmacore.match;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.TemporaryBlockManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;

/** A dead participant's revive banner. Ported from EliteMobs' InstanceDeathLocation. */
final class ReviveBanner {
    private final Match match;
    private final MatchPlayer dead;
    private final ReviveMarker marker;
    private Block bannerBlock;
    private Location deathLocation;
    private TemporaryBlockManager.OwnedBlock lease;
    private BukkitTask watchdog;
    private boolean markerShown;
    private boolean cleared;

    ReviveBanner(Match match, MatchPlayer dead, ReviveMarker marker) {
        this.match = match;
        this.dead = dead;
        this.marker = marker;
        if (dead.lives < 1) {
            cleared = true;
            return;
        }
        if (!relocate(dead.getPlayer().getLocation())) return;
        watch();
    }

    MatchPlayer dead() {
        return dead;
    }

    boolean isPlaced() {
        return !cleared && bannerBlock != null;
    }

    Location respawnLocation() {
        return bannerBlock.getLocation().clone().add(0.5, 0, 0.5);
    }

    private Block findBannerLocation(Location location) {
        // A void death passes the air check immediately and would put the banner where nobody
        // can reach it, so anchor it at the start instead.
        Location start = match.startDestination();
        if (location.getWorld() != null && location.getY() < location.getWorld().getMinHeight() && start != null)
            location = start;
        if (location.getWorld() == null) return null;
        int x = location.getBlockX();
        int z = location.getBlockZ();
        if (!location.getWorld().isChunkLoaded(x >> 4, z >> 4)) return null;
        for (int y = Math.max(location.getBlockY(), location.getWorld().getMinHeight());
             y < location.getWorld().getMaxHeight(); y++) {
            Block candidate = location.getWorld().getBlockAt(x, y, z);
            if (candidate.getType().isAir() && !match.reviveBanners.containsKey(candidate)
                    && TemporaryBlockManager.canOwn(candidate)) return candidate;
        }
        return null;
    }

    private boolean relocate(Location location) {
        releasePlacement();
        Block candidate = findBannerLocation(location.clone());
        if (candidate == null) {
            Logger.warn("Could not place a revive banner for " + dead.getPlayer().getName()
                    + ": no free loaded block below world height.");
            clear(false);
            return false;
        }
        try {
            lease = TemporaryBlockManager.replaceOwned(candidate, Material.RED_BANNER.createBlockData(), MatchCore.plugin());
            if (lease == null) {
                clear(false);
                return false;
            }
            bannerBlock = candidate;
            deathLocation = candidate.getLocation();
            match.reviveBanners.put(candidate, this);
            match.guard("revive marker", () -> marker.show(candidate, dead, dead.lives));
            markerShown = true;
            return true;
        } catch (RuntimeException | LinkageError failure) {
            clear(false);
            throw failure;
        }
    }

    void clear(boolean revive) {
        if (cleared) return;
        cleared = true;
        if (watchdog != null) {
            watchdog.cancel();
            watchdog = null;
        }
        releasePlacement();
        if (revive && bannerBlock != null) match.revive(this);
    }

    private void releasePlacement() {
        if (bannerBlock != null) match.reviveBanners.remove(bannerBlock, this);
        if (lease != null) {
            lease.close();
            lease = null;
        }
        if (markerShown) {
            markerShown = false;
            match.guard("revive marker", marker::hide);
        }
    }

    // Physics updates can remove the banner while it should still be there.
    private void watch() {
        if (cleared || watchdog != null) return;
        watchdog = Bukkit.getScheduler().runTaskTimer(MatchCore.plugin(), () -> {
            if (match.reviveBanners.get(bannerBlock) != this) {
                clear(false);
                return;
            }
            if (bannerBlock.getType() == Material.RED_BANNER) return;
            relocate(deathLocation);
        }, 5L, 5L);
    }
}
