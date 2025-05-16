package com.magmaguy.magmacore;

import com.magmaguy.magmacore.command.CommandManager;
import com.magmaguy.magmacore.command.LogifyCommand;
import com.magmaguy.magmacore.dlc.ConfigurationImporter;
import com.magmaguy.magmacore.instance.InstanceProtector;
import com.magmaguy.magmacore.instance.MatchInstance;
import com.magmaguy.magmacore.instance.MatchInstanceWorld;
import com.magmaguy.magmacore.instance.MatchPlayer;
import com.magmaguy.magmacore.menus.AdvancedMenuHandler;
import com.magmaguy.magmacore.menus.SetupMenu;
import com.magmaguy.magmacore.thirdparty.CustomBiomeCompatibility;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.VersionChecker;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;

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
        Logger.info("MagmaCore v1.13-SNAPSHOT initialized!");
    }

    public static void checkVersionUpdate(String resourceID){
         VersionChecker.checkPluginVersion(resourceID);
        Bukkit.getPluginManager().registerEvents(new VersionChecker.VersionCheckerEvents(), instance.requestingPlugin);
    }

    public static void onEnable() {
        //Register listeners
        Bukkit.getPluginManager().registerEvents(new SetupMenu.SetupMenuListeners(), instance.requestingPlugin);
        Bukkit.getPluginManager().registerEvents(new AdvancedMenuHandler.AdvancedMenuListeners(), instance.requestingPlugin);
        CommandManager commandManager = new CommandManager(instance.requestingPlugin, "logify");
        commandManager.registerCommand(new LogifyCommand(instance.requestingPlugin));
    }

    public static void enableMatchSystem() {
        Bukkit.getPluginManager().registerEvents(new InstanceProtector(), instance.requestingPlugin);
        Bukkit.getPluginManager().registerEvents(new MatchPlayer.MatchPlayerEvents(), instance.requestingPlugin);
        Bukkit.getPluginManager().registerEvents(new MatchInstance.MatchInstanceEvents(), instance.requestingPlugin);
        Bukkit.getPluginManager().registerEvents(new MatchInstanceWorld.MatchInstanceWorldEvents(), instance.requestingPlugin);
    }

    public static MagmaCore createInstance(JavaPlugin requestingPlugin) {
        if (instance == null) return new MagmaCore(requestingPlugin);
        else return instance;
    }

    public static void shutdown() {
        CommandManager.shutdown();
        CustomBiomeCompatibility.shutdown();
        MatchInstance.shutdown();
    }

    public static void initializeImporter() {
        if (instance == null) {
            Bukkit.getLogger().warning("Attempted to initialize importer without first instantiating MagmaCore!");
            return;
        }
        new ConfigurationImporter();
    }
}
