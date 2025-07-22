package com.magmaguy.magmacore.instance;

import com.magmaguy.magmacore.events.MatchInstantiateEvent;
import com.magmaguy.magmacore.util.TemporaryWorldManager;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.io.File;

import static com.magmaguy.magmacore.instance.MatchInstance.MatchInstanceEvents.teleportBypass;

public class MatchInstanceWorld extends MatchInstance implements MatchInstanceInterface {
    @Getter
    private World world;
    private File worldDirectory;

    public MatchInstanceWorld(MatchInstanceConfiguration matchInstanceConfiguration, World world, File worldDirectory) {
        super(matchInstanceConfiguration);
        this.world = world;
        this.worldDirectory = worldDirectory;
        if (matchInstanceConfiguration.isProtected())
            InstanceProtector.addProtectedWorld(world);
        if (matchInstanceConfiguration.isPvpPrevented())
            InstanceProtector.addPvpPreventedWorld(world);
        if (matchInstanceConfiguration.isRedstonePrevented())
            InstanceProtector.addRedstonePreventedWorld(world);
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
        TemporaryWorldManager.permanentlyDeleteWorld(world, worldDirectory); //todo: invest time to make async once I implement a way to see if the plugin is shutting down at destruction time
    }

    @Override
    public boolean isInRegion(Location location) {
        return location.getWorld().equals(world);
    }

    public static class MatchInstanceWorldEvents implements Listener {
        @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
        public void onPlayerTeleport(PlayerTeleportEvent event) {
            if (teleportBypass) {
                teleportBypass = false;
                return;
            }

            for (MatchInstance instance : instances) {
                if (((MatchInstanceWorld) instance).world == null) continue;
                if (((MatchInstanceWorld) instance).world.equals(event.getFrom()) || ((MatchInstanceWorld) instance).world.equals(event.getTo())) {
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
