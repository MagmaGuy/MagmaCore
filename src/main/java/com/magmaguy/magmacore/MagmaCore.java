package com.magmaguy.magmacore;

import com.magmaguy.magmacore.command.AdvancedCommand;
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
import org.bukkit.command.Command;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
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
        Logger.info("MagmaCore v1.13-SNAPSHOT initialized!");
        instance.registerLogify();
    }

    public static void checkVersionUpdate(String resourceID, String downloadURL) {
        VersionChecker.checkPluginVersion(resourceID);
        VersionChecker.VersionCheckerEvents.setDownloadURL(downloadURL);
        Bukkit.getPluginManager().registerEvents(new VersionChecker.VersionCheckerEvents(), instance.requestingPlugin);
    }

    public static void onEnable() {
        //Register listeners
        Bukkit.getPluginManager().registerEvents(new SetupMenu.SetupMenuListeners(), instance.requestingPlugin);
        Bukkit.getPluginManager().registerEvents(new AdvancedMenuHandler.AdvancedMenuListeners(), instance.requestingPlugin);
//        CommandManager commandManager = new CommandManager(instance.requestingPlugin, "logify");
//        commandManager.registerCommand(new LogifyCommand(instance.requestingPlugin));
    }

    public static void enableMatchSystem() {
        Logger.info("Enabling match system...");
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

    private void registerLogify() {
        // 1) grab the server’s CommandMap by reflection
        SimpleCommandMap commandMap = null;
        try {
            Field f = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            f.setAccessible(true);
            commandMap = (SimpleCommandMap) f.get(Bukkit.getServer());
        } catch (ReflectiveOperationException e) {
            requestingPlugin.getLogger().warning("Couldn’t access CommandMap: " + e.getMessage());
            return;
        }

        // 2) ask “does anyone already have a /logify?”
        //    CommandMap#getCommand(String) will return null if nothing registered under that name or any alias.
        if (commandMap.getCommand("logify") != null) {
            requestingPlugin.getLogger().info("/logify is already registered, skipping.");
            return;
        }

        // 3) register the permission (if it doesn’t exist in PManager)
        if (Bukkit.getPluginManager().getPermission("logify.*") == null) {
            Permission perm = new Permission(
                    "logify.*",
                    "Lets admins run the /logify command, which sends the current latest server log to mclo.gs.",
                    PermissionDefault.OP
            );
            Bukkit.getPluginManager().addPermission(perm);
        }

        // 4) finally register it
        commandMap.register(requestingPlugin.getName(), AdvancedCommand.toBukkitCommand(instance.requestingPlugin, new LogifyCommand(instance.requestingPlugin), "logify", new ArrayList<>()));

        // 4) finally register it
        Command wrapper = AdvancedCommand.toBukkitCommand(
                requestingPlugin,
                new LogifyCommand(requestingPlugin),
                "logify",
                List.of()        // aliases if you want any
        );
        commandMap.register(requestingPlugin.getName(), wrapper);


        Logger.info("Registered /logify command");
    }
}
