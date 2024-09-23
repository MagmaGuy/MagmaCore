package com.magmaguy.magmacore;

import com.magmaguy.magmacore.command.CommandManager;
import com.magmaguy.magmacore.dlc.ConfigurationImporter;
import com.magmaguy.magmacore.menus.AdvancedMenu;
import com.magmaguy.magmacore.menus.AdvancedMenuHandler;
import com.magmaguy.magmacore.menus.FirstTimeSetupMenu;
import com.magmaguy.magmacore.menus.SetupMenu;
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
        new AdvancedMenuHandler();
        Logger.info("MagmaCore v1.0 initialized!");
        Logger.info("Sanity check!");
    }

    public static void onEnable(){
        //Register listeners
        Bukkit.getPluginManager().registerEvents(new SetupMenu.SetupMenuListeners(), instance.requestingPlugin);
        Bukkit.getPluginManager().registerEvents(new AdvancedMenuHandler.AdvancedMenuListeners(), instance.requestingPlugin);
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
