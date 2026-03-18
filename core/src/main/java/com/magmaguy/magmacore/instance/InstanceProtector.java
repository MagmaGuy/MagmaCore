package com.magmaguy.magmacore.instance;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;

public class InstanceProtector implements Listener {
    private static final HashSet<UUID> bypassingPlayers = new HashSet<>();
    private static final HashSet<UUID> protectedWorldUUIDs = new HashSet<>();
    private static final HashSet<UUID> pvpPreventedWorldUUIDs = new HashSet<>();
    private static final HashSet<UUID> redstonePreventedWorldUUIDs = new HashSet<>();

    public static void addProtectedWorld(World world) {
        protectedWorldUUIDs.add(world.getUID());
    }

    public static void addPvpPreventedWorld(World world) {
        pvpPreventedWorldUUIDs.add(world.getUID());
    }

    public static void removePvpPreventedWorld(World world) {
        pvpPreventedWorldUUIDs.remove(world.getUID());
    }

    public static void addRedstonePreventedWorld(World world) {
        redstonePreventedWorldUUIDs.add(world.getUID());
    }

    public static void removeRedstonePreventedWorld(World world) {
        redstonePreventedWorldUUIDs.remove(world.getUID());
    }

    private static boolean isProtectedWorld(World world) {
        return protectedWorldUUIDs.contains(world.getUID());
    }

    private static boolean removeProtectedWorld(World world) {
        return protectedWorldUUIDs.remove(world.getUID());
    }

    private static boolean isPvpPreventedWorld(World world) {
        return pvpPreventedWorldUUIDs.contains(world.getUID());
    }

    private static boolean isRedstonePreventedWorld(World world) {
        return redstonePreventedWorldUUIDs.contains(world.getUID());
    }

    public static boolean toggleBypass(UUID playerUUID) {
        if (bypassingPlayers.contains(playerUUID)) {
            bypassingPlayers.remove(playerUUID);
            return false;
        }
        bypassingPlayers.add(playerUUID);
        return true;
    }

    private boolean shouldBypass(Player player) {
        return bypassingPlayers.contains(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void removeProtectedInstance(WorldUnloadEvent event) {
        protectedWorldUUIDs.remove(event.getWorld().getUID());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventPlayerBlockDamage(BlockDamageEvent event) {
        if (!isProtectedWorld(event.getBlock().getWorld())) return;
        if (shouldBypass(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventPlayerBlockBreak(BlockBreakEvent event) {
        if (!isProtectedWorld(event.getBlock().getWorld())) return;
        if (shouldBypass(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventBlockBurnDamage(BlockBurnEvent event) {
        if (!isProtectedWorld(event.getBlock().getWorld())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventPlayerBlockPlace(BlockCanBuildEvent event) {
        if (!isProtectedWorld(event.getBlock().getWorld())) return;
        if (event.getPlayer() != null && shouldBypass(event.getPlayer())) return;
        event.setBuildable(false);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventBlockExplosionEvent(BlockExplodeEvent event) {
        if (!isProtectedWorld(event.getBlock().getWorld())) return;
        // Replace custom logic as needed; for now, cancel the explosion.
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventEntityExplosionEvent(EntityExplodeEvent event) {
        if (!isProtectedWorld(event.getLocation().getWorld())) return;
        event.blockList().clear();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventTntPrimeEvent(TNTPrimeEvent event) {
        if (!isProtectedWorld(event.getBlock().getWorld())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventBlockFadeEvent(BlockFadeEvent event) {
        if (!isProtectedWorld(event.getBlock().getWorld())) return;
        if (event.getBlock().getType().equals(Material.FROSTED_ICE)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventBonemeal(BlockFertilizeEvent event) {
        if (!isProtectedWorld(event.getBlock().getWorld())) return;
        if (event.getPlayer() != null && shouldBypass(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventLiquidFlow(BlockFromToEvent event) {
        if (!isProtectedWorld(event.getBlock().getWorld())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventBlockFire(BlockIgniteEvent event) {
        if (!isProtectedWorld(event.getBlock().getWorld())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventBlockPlace(BlockPlaceEvent event) {
        if (!isProtectedWorld(event.getBlock().getWorld())) return;
        if (shouldBypass(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventLiquidPlace(PlayerBucketEmptyEvent event) {
        if (!isProtectedWorld(event.getBlock().getWorld())) return;
        if (shouldBypass(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventLeafDecay(LeavesDecayEvent event) {
        if (!isProtectedWorld(event.getBlock().getWorld())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventVanillaMobSpawning(CreatureSpawnEvent event) {
        if (event.getSpawnReason().equals(CreatureSpawnEvent.SpawnReason.CUSTOM)) return;
        if (!isProtectedWorld(event.getLocation().getWorld())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventFriendlyFireInDungeon(EntityDamageByEntityEvent event) {
        if (!isPvpPreventedWorld(event.getEntity().getLocation().getWorld())) return;
        if (!(event.getEntity() instanceof Player)) return;
        if (event.getDamager() instanceof Player ||
                (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventDoorOpening(PlayerInteractEvent event) {
        if (!isProtectedWorld(event.getPlayer().getWorld())) return;
        if (event.getClickedBlock() == null) return;
        if (shouldBypass(event.getPlayer())) return;
        Material material = event.getClickedBlock().getType();
        if (material.toString().toLowerCase(Locale.ROOT).endsWith("_sign")) event.setCancelled(true);
        ;
        if (material.toString().toLowerCase(Locale.ROOT).endsWith("_door") ||
                material.toString().toLowerCase(Locale.ROOT).endsWith("_trapdoor"))
            event.setCancelled(true);
        if (event.getAction() == Action.PHYSICAL && isRedstonePreventedWorld(event.getPlayer().getWorld()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventEntityChangeBlockEvent(EntityChangeBlockEvent event) {
        if (!isProtectedWorld(event.getEntity().getWorld())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventCobwebPotions(LingeringPotionSplashEvent event) {
        if (!isProtectedWorld(event.getEntity().getWorld())) return;
        if (event.getEntity().getShooter() == null) return;
        if (!(event.getEntity().getShooter() instanceof Player)) return;
        event.getEntity().getEffects().forEach(potionEffect -> {
            if (potionEffect.getType().equals(PotionEffectType.WEAVING))
                event.setCancelled(true);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventCobwebPotions(PotionSplashEvent event) {
        if (!isProtectedWorld(event.getEntity().getWorld())) return;
        if (event.getEntity().getShooter() == null) return;
        if (!(event.getEntity().getShooter() instanceof Player)) return;
        event.getEntity().getEffects().forEach(potionEffect -> {
            if (potionEffect.getType().equals(PotionEffectType.WEAVING))
                event.setCancelled(true);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventSignEdits(SignChangeEvent event) {
        if (!isProtectedWorld(event.getPlayer().getWorld())) return;
        event.setCancelled(true);
    }
}
