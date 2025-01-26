package com.magmaguy.magmacore;

import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.CommandManager;
import com.magmaguy.magmacore.command.LogifyCommand;
import com.magmaguy.magmacore.dlc.ConfigurationImporter;
import com.magmaguy.magmacore.menus.AdvancedMenu;
import com.magmaguy.magmacore.menus.AdvancedMenuHandler;
import com.magmaguy.magmacore.menus.FirstTimeSetupMenu;
import com.magmaguy.magmacore.menus.SetupMenu;
import com.magmaguy.magmacore.thirdparty.CustomBiomeCompatibility;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class MagmaCore {
    @Getter
    private static MagmaCore instance;
    @Getter
    private final JavaPlugin requestingPlugin;

    private MagmaCore(JavaPlugin requestingPlugin) {
        instance = this;
        this.requestingPlugin = requestingPlugin;
        new AdvancedMenuHandler();
        CustomBiomeCompatibility.initializeMappings();
        Logger.info("MagmaCore v1.7 initialized!");
    }

    public static void onEnable(){
        //Register listeners
        Bukkit.getPluginManager().registerEvents(new SetupMenu.SetupMenuListeners(), instance.requestingPlugin);
        Bukkit.getPluginManager().registerEvents(new AdvancedMenuHandler.AdvancedMenuListeners(), instance.requestingPlugin);
        CommandManager commandManager = new CommandManager(instance.requestingPlugin, "logify");
        commandManager.registerCommand(new LogifyCommand(instance.requestingPlugin));
    }

    public static MagmaCore createInstance(JavaPlugin requestingPlugin) {
        if (instance == null) return new MagmaCore(requestingPlugin);
        else return instance;
    }

    public static void shutdown(){
        CommandManager.shutdown();
        CustomBiomeCompatibility.shutdown();
    }

    public static void initializeImporter(){
        if (instance == null) {
            Bukkit.getLogger().warning("Attempted to initialize importer without first instantiating MagmaCore!");
            return;
        }
        new ConfigurationImporter();
    }
}
