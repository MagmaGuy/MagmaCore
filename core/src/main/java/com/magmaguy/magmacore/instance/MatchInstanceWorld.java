package com.magmaguy.magmacore.instance;

import com.magmaguy.magmacore.events.MatchInstantiateEvent;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.TemporaryWorldManager;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.magmaguy.magmacore.instance.MatchInstance.MatchInstanceEvents.teleportBypass;

public class MatchInstanceWorld extends MatchInstance implements MatchInstanceInterface {
    @Getter
    private List<World> worlds;
    private File worldDirectory;

    public MatchInstanceWorld(MatchInstanceConfiguration matchInstanceConfiguration, World worlds, File worldDirectory) {
        super(matchInstanceConfiguration);
        this.worlds = new ArrayList<>(List.of(worlds));
        this.worldDirectory = worldDirectory;
        if (matchInstanceConfiguration.isProtected())
            InstanceProtector.addProtectedWorld(worlds);
        if (matchInstanceConfiguration.isPvpPrevented())
            InstanceProtector.addPvpPreventedWorld(worlds);
        if (matchInstanceConfiguration.isRedstonePrevented())
            InstanceProtector.addRedstonePreventedWorld(worlds);
    }

    @Override
    public MatchInstantiateEvent start() {
        MatchInstantiateEvent matchInstantiateEvent = super.start();
        if (matchInstantiateEvent.isCancelled()) return matchInstantiateEvent;
        //todo: this is definitely going to require more than just this
        return matchInstantiateEvent;
    }

    @Override
    public void destroyMatch() {
        super.destroyMatch();
        for (World world : worlds) {
            TemporaryWorldManager.permanentlyDeleteWorld(world);
        }
    }

    @Override
    public boolean isInRegion(Location location) {
        for (World world : worlds) {
            if (location.getWorld().equals(world)) return true;
        }
        return false;
    }

    public static class MatchInstanceWorldEvents implements Listener {
        @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
        public void onPlayerTeleport(PlayerTeleportEvent event) {
            Logger.debug("Player " + event.getPlayer().getName() + " teleported to " + event.getTo().getWorld().getName());

            if (teleportBypass) {
                teleportBypass = false;
                return;
            }

            for (MatchInstance instance : instances) {
                if (((MatchInstanceWorld) instance).worlds == null) continue;
                if (((MatchInstanceWorld) instance).worlds.equals(event.getFrom()) || ((MatchInstanceWorld) instance).worlds.equals(event.getTo())) {
                    event.setCancelled(true);
                    return;
                }
            }

            MatchPlayer matchPlayer = MatchPlayer.getMatchPlayer(event.getPlayer());
            if (matchPlayer == null) return;

            if (matchPlayer.getMatchInstance().state == InstanceState.WAITING) {
//                matchPlayer.getMatchInstance().removeAnyKind(matchPlayer); todo: review this one
                return;
            }

            event.setCancelled(true);
        }
    }
}
