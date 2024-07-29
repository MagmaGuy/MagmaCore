package com.magmaguy.magmacore;

import com.magmaguy.magmacore.command.CommandManager;
import com.magmaguy.magmacore.dlc.ConfigurationImporter;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class MagmaCore {
    @Getter
    private static MagmaCore instance;
    @Getter
    private final Plugin requestingPlugin;

    private MagmaCore(Plugin requestingPlugin) {
        instance = this;
        this.requestingPlugin = requestingPlugin;

        Logger.info("MagmaCore v1.0 initialized!");
    }

    public static MagmaCore createInstance(Plugin requestingPlugin) {
        if (instance == null) return new MagmaCore(requestingPlugin);
        else return instance;
    }

    public static void shutdown(){
        CommandManager.shutdown();
    }

    public static void initializeImporter(){
        if (instance == null) {
            Bukkit.getLogger().warning("Attempted to initialize importer without first instantiating MagmaCore!");
            return;
        }
        new ConfigurationImporter();
    }
}
