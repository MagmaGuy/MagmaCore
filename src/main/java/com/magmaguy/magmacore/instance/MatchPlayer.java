package com.magmaguy.magmacore.instance;

import com.magmaguy.magmacore.events.MatchLeaveEvent;
import com.magmaguy.magmacore.util.AttributeManager;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.UUID;

public class MatchPlayer {
    public static HashMap<UUID, MatchPlayer> matchPlayers = new HashMap<>();
    @Getter
    private final Player player;
    @Getter
    private final Location previousLocation;
    @Getter
    private final GameMode previousGameMode;
    @Getter
    private final MatchInstance matchInstance;
    @Getter
    private final Location fallbackLocation;
    @Getter
    @Setter
    private int lives;
    @Getter
    @Setter
    private MatchPlayerType matchPlayerType;
    @Getter
    private DeathLocation deathLocation = null;

    public MatchPlayer(Player player,
                       Location previousLocation,
                       Location fallbackLocation,
                       GameMode previousGameMode,
                       MatchInstance matchInstance,
                       int lives,
                       MatchPlayerType matchPlayerType) {
        this.player = player;
        this.previousLocation = previousLocation;
        this.fallbackLocation = fallbackLocation;
        this.previousGameMode = previousGameMode;
        this.matchInstance = matchInstance;
        this.lives = lives;
        this.matchPlayerType = matchPlayerType;
        matchPlayers.put(player.getUniqueId(), this);
    }

    public static MatchPlayer getMatchPlayer(Player player) {
        return matchPlayers.get(player.getUniqueId());
    }

    public void createDeathLocation() {
        deathLocation = new DeathLocation(player.getLocation().getBlock());
        player.setGameMode(GameMode.SPECTATOR);
    }

    public void revive() {
        player.teleport(deathLocation.block.getLocation());
        deathLocation.block.setType(Material.AIR, false);
        deathLocation = null;
        player.setGameMode(GameMode.SURVIVAL);
    }

    public void sendMessage(String message) {
        Logger.sendSimpleMessage(player, message);
    }

    public void sendTitle(String title, String subtitle) {
        Logger.sendTitle(player, title, subtitle);
    }

    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        Logger.sendTitle(player, title, subtitle, fadeIn, stay, fadeOut);
    }

    public void removeMatchPlayer() {
        if (previousGameMode != null) player.setGameMode(previousGameMode);
        player.setHealth(AttributeManager.getAttributeBaseValue(player, "generic_max_health"));

        if (matchInstance.getMatchInstanceConfiguration().getExitLocation() != null)
            teleport(matchInstance.getMatchInstanceConfiguration().getExitLocation());
        else if (previousLocation != null && previousLocation.getWorld() != null) {
            Logger.debug("teleporting to previous location");
            teleport(previousLocation);
        }
        else teleport(fallbackLocation);

        Bukkit.getPluginManager().callEvent(new MatchLeaveEvent(matchInstance, this));

        if (matchInstance.isPlayer(this))
            matchInstance.players.remove(this);
        if (matchInstance.isSpectator(this))
            matchInstance.spectators.remove(this);
        matchInstance.postPlayerRemovalCheck(this);
    }

    public void teleport(Location location) {
        MatchInstance.MatchInstanceEvents.teleportBypass = true;
        player.teleport(location);
        MatchInstance.MatchInstanceEvents.teleportBypass = false;
    }

    public enum MatchPlayerType {
        PLAYER,
        SPECTATOR
    }

    private record DeathLocation(Block block) {
    }

    public static class MatchPlayerEvents implements Listener {
        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent event) {
            MatchPlayer matchPlayer = matchPlayers.get(event.getPlayer().getUniqueId());
            if (matchPlayer == null) return;
            matchPlayer.removeMatchPlayer();
        }
    }
}
