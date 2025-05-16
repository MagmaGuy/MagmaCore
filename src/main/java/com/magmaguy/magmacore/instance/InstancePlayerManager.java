package com.magmaguy.magmacore.instance;

import com.magmaguy.magmacore.events.MatchJoinEvent;
import com.magmaguy.magmacore.events.MatchLeaveEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class InstancePlayerManager {

//    public static void removePlayer(MatchPlayer matchPlayer, MatchInstance matchInstance) {
//        new MatchLeaveEvent(matchInstance, matchPlayer);
//
//        //Remove match instance where needed
//        PlayerData.setMatchInstance(matchPlayer, null);
//        matchInstance.players.remove(matchPlayer);
//        if (!matchInstance.spectators.contains(matchPlayer)) {
//            matchInstance.participants.remove(matchPlayer);
//            PlayerData.setMatchInstance(matchPlayer, null);
//        }
//
//        if (matchInstance.players.isEmpty() && matchInstance.getDeathLocationByPlayer(matchPlayer) != null)
//            matchInstance.getDeathLocationByPlayer(matchPlayer).clear(false);
//
//        //Teleport the player out
//        if (matchPlayer.isOnline()) {
//            MatchInstance.MatchInstanceEvents.teleportBypass = true;
//            if (matchInstance instanceof DungeonInstance) {
//                Location location = matchInstance.previousPlayerLocations.get(matchPlayer);
//                if (location != null) matchPlayer.teleport(location);
//                else matchPlayer.teleport(matchInstance.exitLocation);
//            } else
//                matchPlayer.teleport(matchInstance.exitLocation);
//        }
//
//        //End the match if there are no players left because they all died
//        if (matchInstance.state != MatchInstance.InstancedRegionState.COMPLETED &&
//                matchInstance.state != MatchInstance.InstancedRegionState.COMPLETED_DEFEAT &&
//                matchInstance.state != MatchInstance.InstancedRegionState.COMPLETED_VICTORY &&
//                matchInstance.players.isEmpty()) {
//            matchInstance.defeat();
//        } else
//            //Remove lives
//            matchInstance.playerLives.remove(matchPlayer);
//    }
//
//    public static void playerDeath(MatchInstance matchInstance, MatchPlayer matchPlayer) {
//        if (!matchInstance.players.contains(matchPlayer)) return;
//        AlternativeDurabilityLoss.doDurabilityLoss(matchPlayer);
//        AttributeManager.setAttribute(matchPlayer, "generic_max_health", AttributeManager.getAttributeBaseValue(matchPlayer, "generic_max_health"));
//        matchInstance.players.remove(matchPlayer);
//        if (matchInstance.players.isEmpty()) {
//            matchInstance.defeat();
//            MatchInstance.MatchInstanceEvents.teleportBypass = true;
//            if (matchInstance.previousPlayerLocations.get(matchPlayer) != null)
//                matchPlayer.teleport(matchInstance.previousPlayerLocations.get(matchPlayer));
//            else if (matchInstance.exitLocation != null)
//                matchPlayer.teleport(matchInstance.exitLocation);
//            PlayerData.setMatchInstance(matchPlayer, null);
//            matchInstance.participants.remove(matchPlayer);
//            return;
//        }
//        new InstanceDeathLocation(matchPlayer, matchInstance);
//        matchInstance.makeSpectator(matchPlayer, true);
//    }
//
//    public static void revivePlayer(MatchInstance matchInstance, MatchPlayer matchPlayer, InstanceDeathLocation deathLocation) {
//        matchInstance.playerLives.put(matchPlayer, matchInstance.playerLives.get(matchPlayer) - 1);
//        matchInstance.players.add(matchPlayer);
//        matchPlayer.setGameMode(GameMode.SURVIVAL);
//        matchInstance.spectators.remove(matchPlayer);
//        MatchInstance.MatchInstanceEvents.teleportBypass = true;
//        matchPlayer.teleport(deathLocation.getBannerBlock().getLocation());
//        PlayerData.setMatchInstance(matchPlayer, matchInstance);
//    }
//
//    public static void makeSpectator(MatchInstance matchInstance, MatchPlayer matchPlayer) {
//        matchInstance.participants.add(matchPlayer);
//        matchPlayer.sendMessage(ArenasConfig.getArenaJoinSpectatorMessage());
//        matchPlayer.sendTitle(ArenasConfig.getJoinSpectatorTitle(), ArenasConfig.getJoinSpectatorSubtitle(), 60, 60 * 3, 60);
//        matchInstance.spectators.add(matchPlayer);
//        matchPlayer.setGameMode(GameMode.SPECTATOR);
//        if (!wasPlayer) {
//            MatchInstance.MatchInstanceEvents.teleportBypass = true;
//        }
//        PlayerData.setMatchInstance(matchPlayer, matchInstance);
//    }
//
//    public static void removeSpectator(MatchInstance matchInstance, MatchPlayer matchPlayer) {
//        matchInstance.spectators.remove(matchPlayer);
//        if (!matchInstance.players.contains(matchPlayer)) {
//            PlayerData.setMatchInstance(matchPlayer, null);
//            matchInstance.participants.remove(matchPlayer);
//        }
//        matchPlayer.setGameMode(GameMode.SURVIVAL);
//        MatchInstance.MatchInstanceEvents.teleportBypass = true;
//        if (matchInstance instanceof DungeonInstance) {
//            Location location = matchInstance.previousPlayerLocations.get(matchPlayer);
//            if (location != null) matchPlayer.teleport(location);
//            else matchPlayer.teleport(matchInstance.exitLocation);
//        } else
//            matchPlayer.teleport(matchInstance.exitLocation);
//        PlayerData.setMatchInstance(matchPlayer, null);
//        matchInstance.playerLives.remove(matchPlayer);
//        if (matchInstance.getDeathLocationByPlayer(matchPlayer) != null)
//            matchInstance.getDeathLocationByPlayer(matchPlayer).clear(false);
//    }
//
//    public static void removeAnyKind(MatchInstance matchInstance, MatchPlayer matchPlayer) {
//        if (matchInstance.players.contains(matchPlayer)) matchInstance.removePlayer(matchPlayer);
//        if (matchInstance.spectators.contains(matchPlayer)) matchInstance.removeSpectator(matchPlayer);
//        matchInstance.participants.remove(matchPlayer);
//        PlayerData.setMatchInstance(matchPlayer, null);
//    }

}

