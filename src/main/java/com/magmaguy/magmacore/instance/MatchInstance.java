package com.magmaguy.magmacore.instance;


import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.events.MatchDestroyEvent;
import com.magmaguy.magmacore.events.MatchInstantiateEvent;
import com.magmaguy.magmacore.events.MatchJoinEvent;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public abstract class MatchInstance implements MatchInstanceInterface {
    @Getter
    protected static final HashSet<MatchInstance> instances = new HashSet<>();
    @Getter
    private final MatchInstanceConfiguration matchInstanceConfiguration;
    @Getter
    protected HashSet<MatchPlayer> players = new HashSet<>();
    protected HashSet<MatchPlayer> spectators = new HashSet<>();
    @Getter
    protected InstanceState state = InstanceState.WAITING;
    @Getter
    protected String permission = null;
    private TickTask tick = null;

    public MatchInstance(MatchInstanceConfiguration matchInstanceConfiguration) {
        this.matchInstanceConfiguration = matchInstanceConfiguration;
        instances.add(this);
    }

    public static void shutdown() {
        HashSet<MatchInstance> cloneInstance = new HashSet<>(instances);
        cloneInstance.forEach(MatchInstance::destroyMatch);
        instances.clear();
    }

    public MatchInstantiateEvent start() {
        MatchInstantiateEvent matchInstantiateEvent = new MatchInstantiateEvent(this);
        if (matchInstantiateEvent.isCancelled()) {
            return matchInstantiateEvent;
        }

        tick = new TickTask();
        tick.runTaskTimer(MagmaCore.getInstance().getRequestingPlugin(), 0, 1);

        countdownMatch();
        return matchInstantiateEvent;
    }

    public boolean addNewPlayer(Player player) {
        MatchJoinEvent event = new MatchJoinEvent(this, player);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;

        if (getMatchInstanceConfiguration().getMatchGamemode() != null)
            player.setGameMode(getMatchInstanceConfiguration().getMatchGamemode());

        //Check permissions
        if (getMatchInstanceConfiguration().getDungeonPermission() != null && !player.hasPermission(getMatchInstanceConfiguration().getDungeonPermission())) {
            player.sendMessage(matchInstanceConfiguration.getFailedToJoinOngoingMatchAsPlayerNoPermission());
            return false;
        }

        //New players can't join ongoing instances
        if (!state.equals(InstanceState.WAITING)) {
            event.setCancelled(true);
            player.sendMessage(matchInstanceConfiguration.getFailedToJoinOngoingMatchAsPlayerMessage());
            return false;
        }

        //Check if match is full
        if (players.size() + 1 > matchInstanceConfiguration.getMaxPlayers()) {
            player.sendMessage(matchInstanceConfiguration.getFailedToJoinOngoingMatchAsPlayerInstanceIsFull());
            return false;
        }

        MatchPlayer matchPlayer = new MatchPlayer(player,
                player.getLocation(),
                matchInstanceConfiguration.getFallbackLocation(),
                player.getGameMode(),
                this,
                getMatchInstanceConfiguration().getLives(),
                MatchPlayer.MatchPlayerType.PLAYER);
        players.add(matchPlayer);
        return initializeNewPlayerOrSpectator(matchPlayer, players);
    }

    public boolean addNewSpectator(Player player) {
        if (!getMatchInstanceConfiguration().isSpectatable()) return false;

        MatchJoinEvent event = new MatchJoinEvent(this, player);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;

        //Check permissions
        if (getMatchInstanceConfiguration().getDungeonPermission() != null && !player.hasPermission(getMatchInstanceConfiguration().getDungeonPermission())) {
            player.sendMessage(matchInstanceConfiguration.getFailedToJoinOngoingMatchAsPlayerNoPermission());
            return false;
        }

        MatchPlayer matchPlayer = new MatchPlayer(
                player,
                player.getLocation(),
                matchInstanceConfiguration.getFallbackLocation(),
                player.getGameMode(),
                this,
                getMatchInstanceConfiguration().getLives(),
                MatchPlayer.MatchPlayerType.SPECTATOR);
        spectators.add(matchPlayer);
        return initializeNewPlayerOrSpectator(matchPlayer, spectators);
    }

    private boolean initializeNewPlayerOrSpectator(MatchPlayer matchPlayer, HashSet<MatchPlayer> playerOrSpectatorSet) {
        playerOrSpectatorSet.add(matchPlayer);
        matchPlayer.sendMessage(matchInstanceConfiguration.getMatchJoinAsPlayerMessage().replace("$count", matchInstanceConfiguration.getMinPlayers() + ""));
        matchPlayer.sendTitle(matchInstanceConfiguration.getMatchJoinAsPlayerTitle(), matchInstanceConfiguration.getMatchStartingSubtitle(), 60, 60 * 3, 60);

        new BukkitRunnable() {
            @Override
            public void run() {
                //Teleport the player to the correct location
                if (matchInstanceConfiguration.getLobbyLocation() != null && state.equals(InstanceState.WAITING))
                    matchPlayer.teleport(matchInstanceConfiguration.getLobbyLocation());
                else if (matchInstanceConfiguration.getStartLocation() != null)
                    matchPlayer.teleport(matchInstanceConfiguration.getStartLocation());
            }
        }.runTaskLater(MagmaCore.getInstance().getRequestingPlugin(), 1);

        return true;
    }

    public void postPlayerRemovalCheck(MatchPlayer matchPlayer) {
        if (players.isEmpty()) endMatch();
    }

    public void playerDeath(MatchPlayer matchPlayer) {
        matchPlayer.setLives(matchPlayer.getLives() - 1);
        if (matchPlayer.getLives() > 0) {
            //todo implement rez system
        } else {
            matchPlayer.removeMatchPlayer();
        }
    }

//    public void revivePlayer(MatchPlayer matchPlayer, InstanceDeathLocation deathLocation) {
//        InstancePlayerManager.revivePlayer(this, matchPlayer, deathLocation);
//    }

    public void makeSpectator(MatchPlayer matchPlayer) {
        if (!matchInstanceConfiguration.isSpectatable()) return;
        players.remove(matchPlayer);
        spectators.add(matchPlayer);
    }

    public boolean isPlayer(MatchPlayer matchPlayer) {
        return players.contains(matchPlayer);
    }

    public boolean isSpectator(MatchPlayer matchPlayer) {
        return spectators.contains(matchPlayer);
    }

    public void removeSpectator(MatchPlayer matchPlayer) {
        matchPlayer.removeMatchPlayer();
        spectators.remove(matchPlayer);
    }

    public void countdownMatch() {
        if (state != InstanceState.WAITING) return;
        if (players.size() < matchInstanceConfiguration.getMinPlayers()) {
            announceChat(matchInstanceConfiguration.getMatchFailedToStartNotEnoughPlayersMessage().replace("$amount", matchInstanceConfiguration.getMinPlayers() + ""));
            return;
        }
        state = InstanceState.STARTING;
        new CountdownTask().runTaskTimer(MagmaCore.getInstance().getRequestingPlugin(), 0L, 20L);
    }

    private void playerWatchdog() {
        ((HashSet<MatchPlayer>) players.clone()).forEach(matchPlayer -> {
            if (!matchPlayer.getPlayer().isOnline()) matchPlayer.removeMatchPlayer();
            if (!isInRegion(matchPlayer.getPlayer().getLocation())) {
                MatchInstanceEvents.teleportBypass = true;
                matchPlayer.teleport(matchInstanceConfiguration.getStartLocation());
            }
        });
    }

    private void spectatorWatchdog() {
        ((HashSet<MatchPlayer>) spectators.clone()).forEach(matchPlayer -> {
            if (!matchPlayer.getPlayer().isOnline()) removeSpectator(matchPlayer);
            if (!isInRegion(matchPlayer.getPlayer().getLocation())) {
                MatchInstanceEvents.teleportBypass = true;
                matchPlayer.teleport(matchInstanceConfiguration.getStartLocation());
            }
        });
    }

    private void intruderWatchdog() {
        if (state != InstanceState.ONGOING) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            MatchPlayer matchPlayer = MatchPlayer.getMatchPlayer(player);
            if ((matchPlayer == null || !matchPlayer.getMatchInstance().equals(this)) && isInRegion(player.getLocation()))
                kickPlayerOut(player);
        }
    }

    private void kickPlayerOut(Player player) {
        if (player.isOp()) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("You are intruding on a match, but won't get kicked you because you're an OP!"));
            return;
        }
        MatchPlayer matchPlayer = MatchPlayer.getMatchPlayer(player);
        if (matchPlayer != null) matchPlayer.removeMatchPlayer();
    }

    public void announceChat(String message) {
        getAllParticipants().forEach(matchPlayer -> matchPlayer.sendMessage(message));
    }

    public void announceTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        getAllParticipants().forEach(matchPlayer -> matchPlayer.sendTitle(title, subtitle, fadeIn, stay, fadeOut));
    }

    protected List<MatchPlayer> getAllParticipants() {
        List<MatchPlayer> matchPlayers = new ArrayList<>();
        matchPlayers.addAll(players);
        matchPlayers.addAll(spectators);
        return matchPlayers;
    }

    private void startMessage(int counter, MatchPlayer matchPlayer) {
        matchPlayer.getPlayer().sendTitle(
                matchInstanceConfiguration.getMatchStartingTitle().replace("$count", (3 - counter) + ""),
                matchInstanceConfiguration.getMatchStartingSubtitle().replace("$count", (3 - counter) + ""), 0, 20, 0);
    }

    public abstract boolean isInRegion(Location location);

    protected void startMatch() {
        state = InstanceState.ONGOING;
        players.forEach(matchPlayer -> {
            if (matchInstanceConfiguration.getStartLocation() != null)
                matchPlayer.getPlayer().teleport(matchInstanceConfiguration.getStartLocation());
        });
    }

    /*
    This is useful for extending behavior down the line like the enchanted dungeon loot
     */
    protected void victory() {
        state = InstanceState.COMPLETED_VICTORY;
        endMatch();
    }

    protected void defeat() {
        state = InstanceState.COMPLETED_DEFEAT;
        endMatch();
    }

    protected void endMatch() {
        if (state != InstanceState.COMPLETED_VICTORY &&
                state != InstanceState.COMPLETED_DEFEAT)
            state = InstanceState.COMPLETED;
        Logger.debug("MATCH ENDED");
        destroyMatch();
        //todo this should probable call resetMatch() and resetMatch() should probably be renamed because that's not what it does
//        Bukkit.getPluginManager().callEvent(new MatchEndEvent(this)); todo: make MatchEndEvent
    }

    protected void destroyMatch() {
        state = InstanceState.WAITING;
        List<MatchPlayer> copy = getAllParticipants();
        copy.forEach(MatchPlayer::removeMatchPlayer);
        players.clear();
        spectators.clear();
//        deathBanners.values().forEach(deathLocation -> deathLocation.clear(false));
//        deathBanners.clear();
        if (tick != null && !tick.isCancelled())
            tick.cancel();
        Bukkit.getPluginManager().callEvent(new MatchDestroyEvent(this));
    }

//    protected InstanceDeathLocation getDeathLocationByPlayer(Player player) {
//        for (InstanceDeathLocation deathLocation : deathBanners.values())
//            if (deathLocation.getDeadPlayer().equals(player))
//                return deathLocation;
//        return null;
//    }

    public enum InstanceState {
        WAITING, STARTING, ONGOING, COMPLETED, COMPLETED_VICTORY, COMPLETED_DEFEAT
    }

    public static class MatchInstanceEvents implements Listener {
        public static boolean teleportBypass = false;

        @EventHandler
        public void onPlayerBreakBlockEvent(BlockBreakEvent event) {
//            for (MatchInstance matchInstance : instances)
//                if (matchInstance.state.equals(InstancedRegionState.ONGOING))
//                    if (matchInstance.getDeathBanners().get(event.getBlock()) != null)
//                        matchInstance.getDeathBanners().get(event.getBlock()).clear(true);
        }

        @EventHandler
        public void onPlayerHitFlagEvent(BlockDamageEvent event) {
//            for (MatchInstance matchInstance : instances)
//                if (matchInstance.state.equals(InstancedRegionState.ONGOING))
//                    if (matchInstance.getDeathBanners().get(event.getBlock()) != null)
//                        matchInstance.getDeathBanners().get(event.getBlock()).clear(true);
        }

        /**
         * This event scans for damage that would kill the player and cancels it with custom behavior it if would
         *
         * @param event Damage event
         */
        @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
        public void onPlayerDamage(EntityDamageEvent event) {
            if (!event.getEntityType().equals(EntityType.PLAYER)) return;
            MatchPlayer matchPlayer = MatchPlayer.getMatchPlayer((Player) event.getEntity());
            if (matchPlayer == null) return;
            if (event.getFinalDamage() < matchPlayer.getPlayer().getHealth()) return;
            if (matchPlayer.getMatchInstance().state != InstanceState.ONGOING)
                matchPlayer.removeMatchPlayer();
            event.setCancelled(true);
            matchPlayer.getMatchInstance().playerDeath(matchPlayer);
        }
    }

    private class TickTask extends BukkitRunnable {
        @Override
        public void run() {
            playerWatchdog();
            spectatorWatchdog();
            intruderWatchdog();
        }
    }

    private class CountdownTask extends BukkitRunnable {
        int counter = 0;

        @Override
        public void run() {
            if (players.size() < matchInstanceConfiguration.getMinPlayers()) {
                cancel();
                endMatch();
                return;
            }
            counter++;
            players.forEach(matchPlayer -> startMessage(counter, matchPlayer));
            spectators.forEach(matchPlayer -> startMessage(counter, matchPlayer));
            if (counter >= 3) {
                startMatch();
                cancel();
            }
        }
    }

}
