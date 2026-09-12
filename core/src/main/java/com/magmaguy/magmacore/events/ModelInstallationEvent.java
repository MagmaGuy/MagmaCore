package com.magmaguy.magmacore.events;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class ModelInstallationEvent extends Event {
    private static final HandlerList handlers = new HandlerList();

    public ModelInstallationEvent() {
        Logger.info("Models have been installed!");
        Plugin models = Bukkit.getPluginManager().getPlugin("FreeMinecraftModels");
        if (models == null || !models.isEnabled()) return;
        try {
            // MagmaCore compiles against older optional FMM APIs. Resolve the existing
            // content reload entrypoint without replacing it with a full plugin teardown.
            models.getClass().getMethod("reloadImportedContent", CommandSender.class)
                    .invoke(models, Bukkit.getConsoleSender());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FreeMinecraftModels could not reload imported content. "
                    + "Install a current compatible FreeMinecraftModels build.", failure);
        }
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }
}
