package com.magmaguy.magmacore.events;

import com.magmaguy.freeminecraftmodels.commands.ReloadCommand;
import com.magmaguy.magmacore.menus.SetupMenu;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class ModelInstallationEvent extends Event {
    private static final HandlerList handlers = new HandlerList();

    public ModelInstallationEvent() {
        Logger.info("Models have been installed!");
        //FMM reload
        ReloadCommand.reloadPlugin(Bukkit.getConsoleSender());
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }
}
