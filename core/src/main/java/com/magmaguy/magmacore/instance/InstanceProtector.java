package com.magmaguy.magmacore.instance;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * World-scoped protections, applied at EventPriority.LOWEST to intercept
 * before other plugins. Originally extracted from EliteMobs' DungeonProtector;
 * see {@link WorldProtectionRules} for per-world toggles and
 * {@link ContainerAllowlist} for the optional treasure-chest hook.
 *
 * Plugins register a world with {@link #addProtectedWorld(World, WorldProtectionRules)}.
 * Worlds are automatically removed on WorldUnloadEvent.
 */
public class InstanceProtector implements Listener {

    private static final Map<UUID, WorldProtectionRules> protectedWorlds = new HashMap<>();
    private static final HashSet<UUID> bypassingPlayers = new HashSet<>();
    private static ContainerAllowlist containerAllowlist;

    public static void addProtectedWorld(World world) {
        addProtectedWorld(world, WorldProtectionRules.strict());
    }

    public static void addProtectedWorld(World world, WorldProtectionRules rules) {
        protectedWorlds.put(world.getUID(), rules);
    }

    public static boolean removeProtectedWorld(World world) {
        return protectedWorlds.remove(world.getUID()) != null;
    }

    public static boolean isProtectedWorld(World world) {
        return world != null && protectedWorlds.containsKey(world.getUID());
    }

    public static WorldProtectionRules getRules(World world) {
        return world == null ? null : protectedWorlds.get(world.getUID());
    }

    public static void setContainerAllowlist(ContainerAllowlist allowlist) {
        containerAllowlist = allowlist;
    }

    /** Returns the new bypass state for the given player. */
    public static boolean toggleBypass(UUID playerUUID) {
        if (bypassingPlayers.contains(playerUUID)) {
            bypassingPlayers.remove(playerUUID);
            return false;
        }
        bypassingPlayers.add(playerUUID);
        return true;
    }

    public static void shutdown() {
        protectedWorlds.clear();
        bypassingPlayers.clear();
        containerAllowlist = null;
    }

    // ---- legacy compatibility shims (older callers) ----
    public static void addPvpPreventedWorld(World world) {
        WorldProtectionRules rules = protectedWorlds.computeIfAbsent(world.getUID(), k -> WorldProtectionRules.strict());
        rules.setPreventFriendlyFire(true);
    }

    public static void removePvpPreventedWorld(World world) {
        WorldProtectionRules rules = protectedWorlds.get(world.getUID());
        if (rules != null) rules.setPreventFriendlyFire(false);
    }

    public static void addRedstonePreventedWorld(World world) {
        WorldProtectionRules rules = protectedWorlds.computeIfAbsent(world.getUID(), k -> WorldProtectionRules.strict());
        rules.setPreventRedstoneInteraction(true);
    }

    public static void removeRedstonePreventedWorld(World world) {
        WorldProtectionRules rules = protectedWorlds.get(world.getUID());
        if (rules != null) rules.setPreventRedstoneInteraction(false);
    }

    private boolean shouldBypass(Player player) {
        return player != null && bypassingPlayers.contains(player.getUniqueId());
    }

    private static WorldProtectionRules rulesFor(World world) {
        return world == null ? null : protectedWorlds.get(world.getUID());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void removeProtectedInstance(WorldUnloadEvent event) {
        protectedWorlds.remove(event.getWorld().getUID());
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
        WorldProtectionRules rules = rulesFor(event.getBlock().getWorld());
        if (rules == null) return;
        if (!rules.isAllowExplosions()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventEntityExplosionEvent(EntityExplodeEvent event) {
        WorldProtectionRules rules = rulesFor(event.getLocation().getWorld());
        if (rules == null) return;
        if (!rules.isAllowExplosions()) event.blockList().clear();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventTntPrimeEvent(TNTPrimeEvent event) {
        WorldProtectionRules rules = rulesFor(event.getBlock().getWorld());
        if (rules == null) return;
        if (!rules.isAllowExplosions()) event.setCancelled(true);
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
        WorldProtectionRules rules = rulesFor(event.getBlock().getWorld());
        if (rules == null) return;
        if (rules.isAllowLiquidFlow()) return;
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
        WorldProtectionRules rules = rulesFor(event.getLocation().getWorld());
        if (rules == null) return;
        if (rules.isPreventVanillaMobSpawning()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventFriendlyFireInDungeon(EntityDamageByEntityEvent event) {
        WorldProtectionRules rules = rulesFor(event.getEntity().getWorld());
        if (rules == null || !rules.isPreventFriendlyFire()) return;
        if (!(event.getEntity() instanceof Player)) return;
        if (event.getDamager() instanceof Player
                || (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventDoorOpening(PlayerInteractEvent event) {
        WorldProtectionRules rules = rulesFor(event.getPlayer().getWorld());
        if (rules == null) return;
        if (event.getClickedBlock() == null) return;
        if (shouldBypass(event.getPlayer())) return;
        Material material = event.getClickedBlock().getType();
        String name = material.toString().toLowerCase(Locale.ROOT);
        if (name.endsWith("_door") || name.endsWith("_trapdoor") || name.endsWith("_fence_gate")) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() == Action.PHYSICAL && rules.isPreventRedstoneInteraction())
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
            if (potionEffect.getType().equals(PotionEffectType.WEAVING)) event.setCancelled(true);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventCobwebPotions(PotionSplashEvent event) {
        if (!isProtectedWorld(event.getEntity().getWorld())) return;
        if (event.getEntity().getShooter() == null) return;
        if (!(event.getEntity().getShooter() instanceof Player)) return;
        event.getEntity().getEffects().forEach(potionEffect -> {
            if (potionEffect.getType().equals(PotionEffectType.WEAVING)) event.setCancelled(true);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventSignEdits(SignChangeEvent event) {
        if (!isProtectedWorld(event.getPlayer().getWorld())) return;
        if (shouldBypass(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventContainerAccess(PlayerInteractEvent event) {
        if (!isProtectedWorld(event.getPlayer().getWorld())) return;
        if (shouldBypass(event.getPlayer())) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Material type = block.getType();
        if (type == Material.CHEST || type == Material.TRAPPED_CHEST
                || type == Material.BARREL || type == Material.SHULKER_BOX
                || type.name().endsWith("_SHULKER_BOX")) {
            if (containerAllowlist != null && containerAllowlist.isAllowed(block)) return;
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventDragonEggInteraction(PlayerInteractEvent event) {
        if (!isProtectedWorld(event.getPlayer().getWorld())) return;
        if (shouldBypass(event.getPlayer())) return;
        Block block = event.getClickedBlock();
        if (block != null && block.getType() == Material.DRAGON_EGG) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventFlowerpotInteraction(PlayerInteractEvent event) {
        if (!isProtectedWorld(event.getPlayer().getWorld())) return;
        if (shouldBypass(event.getPlayer())) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        String name = block.getType().name();
        if (block.getType() == Material.FLOWER_POT || name.startsWith("POTTED_")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventElytraGlideStart(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!event.isGliding()) return;
        WorldProtectionRules rules = rulesFor(player.getWorld());
        if (rules == null || rules.isAllowElytra()) return;
        if (shouldBypass(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventElytraOnWorldEnter(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!player.isGliding()) return;
        WorldProtectionRules rules = rulesFor(player.getWorld());
        if (rules == null || rules.isAllowElytra()) return;
        if (shouldBypass(player)) return;
        player.setGliding(false);
    }

    /*
     * Backstop for client-side desync: cancelling EntityToggleGlideEvent does not
     * reliably stop gliding on the client in 1.21.6+, so we re-assert setGliding(false)
     * on every move tick while the player is gliding in a protected world. Cheap —
     * early-exits for non-gliders.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void enforceNoElytraInProtectedWorld(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!player.isGliding()) return;
        WorldProtectionRules rules = rulesFor(player.getWorld());
        if (rules == null || rules.isAllowElytra()) return;
        if (shouldBypass(player)) return;
        player.setGliding(false);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventFlyToggle(PlayerToggleFlightEvent event) {
        WorldProtectionRules rules = rulesFor(event.getPlayer().getWorld());
        if (rules == null || !rules.isPreventFlyToggle()) return;
        if (event.getPlayer().isOp()) return;
        if (shouldBypass(event.getPlayer())) return;
        if (event.isFlying()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventItemFrameBreak(HangingBreakByEntityEvent event) {
        if (!isProtectedWorld(event.getEntity().getWorld())) return;
        if (event.getRemover() instanceof Player player) {
            if (shouldBypass(player)) return;
        } else if (event.getRemover() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            if (shouldBypass(player)) return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventItemFrameDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame)) return;
        if (!isProtectedWorld(event.getEntity().getWorld())) return;
        if (event.getDamager() instanceof Player player) {
            if (shouldBypass(player)) return;
        } else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            if (shouldBypass(player)) return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventItemFrameInteract(PlayerInteractEntityEvent event) {
        if (!isProtectedWorld(event.getPlayer().getWorld())) return;
        if (shouldBypass(event.getPlayer())) return;
        if (event.getRightClicked() instanceof ItemFrame) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (!isProtectedWorld(event.getPlayer().getWorld())) return;
        if (shouldBypass(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventArmorStandDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ArmorStand)) return;
        if (!isProtectedWorld(event.getEntity().getWorld())) return;
        if (event.getDamager() instanceof Player player) {
            if (shouldBypass(player)) return;
        } else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            if (shouldBypass(player)) return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void applyEnvironmentalDamageMultipliers(EntityDamageEvent event) {
        WorldProtectionRules rules = rulesFor(event.getEntity().getWorld());
        if (rules == null) return;
        switch (event.getCause()) {
            case POISON -> event.setDamage(event.getDamage() * rules.getPoisonDamageMultiplier());
            case WITHER -> event.setDamage(event.getDamage() * rules.getWitherDamageMultiplier());
            case FIRE_TICK -> event.setDamage(event.getDamage() * rules.getFireDamageMultiplier());
            default -> { /* no-op */ }
        }
    }
}
