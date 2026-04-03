package com.magmaguy.magmacore;

import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandManager;
import com.magmaguy.magmacore.command.LogifyCommand;
import com.magmaguy.magmacore.command.NightbreakLoginCommand;
import com.magmaguy.magmacore.dlc.ConfigurationImporter;
import com.magmaguy.magmacore.initialization.PluginInitializationConfig;
import com.magmaguy.magmacore.initialization.PluginInitializationContext;
import com.magmaguy.magmacore.initialization.PluginInitializationManager;
import com.magmaguy.magmacore.initialization.PluginInitializationState;
import com.magmaguy.magmacore.instance.InstanceProtector;
import com.magmaguy.magmacore.instance.MatchInstance;
import com.magmaguy.magmacore.instance.MatchInstanceWorld;
import com.magmaguy.magmacore.instance.MatchPlayer;
import com.magmaguy.magmacore.menus.AdvancedMenuHandler;
import com.magmaguy.magmacore.menus.SetupMenu;
import com.magmaguy.magmacore.nightbreak.NightbreakAccount;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginStateRegistry;
import com.magmaguy.magmacore.thirdparty.CustomBiomeCompatibility;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.TemporaryBlockManager;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class MagmaCore {
    @Getter
    private static MagmaCore instance;
    private static final Map<String, JavaPlugin> registeredPlugins = new HashMap<>();
    private static final Set<String> listenerRegistrations = new HashSet<>();
    @Getter
    private final JavaPlugin requestingPlugin;

    private MagmaCore(JavaPlugin requestingPlugin) {
        instance = this;
        this.requestingPlugin = requestingPlugin;
        new AdvancedMenuHandler();
        CustomBiomeCompatibility.initializeMappings();
        Logger.info("MagmaCore v1.29-SNAPSHOT initialized!");
        instance.registerLogify();
        instance.registerNightbreakLogin();
        NightbreakAccount.initialize(requestingPlugin);
    }

    public static void checkVersionUpdate(String resourceID, String downloadURL) {
        VersionChecker.checkPluginVersion(resourceID);
        VersionChecker.VersionCheckerEvents.setDownloadURL(downloadURL);
        Bukkit.getPluginManager().registerEvents(new VersionChecker.VersionCheckerEvents(), instance.requestingPlugin);
    }

    public static void onEnable() {
        onEnable(instance.requestingPlugin);
    }

    public static void onEnable(JavaPlugin plugin) {
        //Register listeners
        if (plugin == null) return;
        if (!listenerRegistrations.add(plugin.getName())) return;
        Bukkit.getPluginManager().registerEvents(new SetupMenu.SetupMenuListeners(), plugin);
        Bukkit.getPluginManager().registerEvents(new AdvancedMenuHandler.AdvancedMenuListeners(), plugin);
        TemporaryBlockManager.initialize(plugin);
//        CommandManager commandManager = new CommandManager(instance.requestingPlugin, "logify");
//        commandManager.registerCommand(new LogifyCommand(instance.requestingPlugin));
    }

    public static void enableMatchSystem() {
        enableMatchSystem(instance.requestingPlugin);
    }

    public static void enableMatchSystem(JavaPlugin plugin) {
        Logger.info("Enabling match system...");
        Bukkit.getPluginManager().registerEvents(new InstanceProtector(), plugin);
        Bukkit.getPluginManager().registerEvents(new MatchPlayer.MatchPlayerEvents(), plugin);
        Bukkit.getPluginManager().registerEvents(new MatchInstance.MatchInstanceEvents(), plugin);
        Bukkit.getPluginManager().registerEvents(new MatchInstanceWorld.MatchInstanceWorldEvents(), plugin);
    }

    public static MagmaCore createInstance(JavaPlugin requestingPlugin) {
        registeredPlugins.put(requestingPlugin.getName(), requestingPlugin);
        if (instance == null) {
            return new MagmaCore(requestingPlugin);
        }
        NightbreakAccount.initialize(requestingPlugin);
        return instance;
    }

    public static void shutdown() {
        CommandManager.shutdown();
        CustomBiomeCompatibility.shutdown();
        MatchInstance.shutdown();
        TemporaryBlockManager.shutdown();
    }

    public static void shutdown(JavaPlugin plugin) {
        if (plugin != null) {
            registeredPlugins.remove(plugin.getName());
            listenerRegistrations.remove(plugin.getName());
            PluginInitializationManager.shutdown(plugin);
            NightbreakPluginStateRegistry.clear(plugin);
        }
        shutdown();
    }

    public static void initializeImporter() {
        initializeImporter(instance.requestingPlugin);
    }

    public static void initializeImporter(JavaPlugin plugin) {
        if (instance == null) {
            Bukkit.getLogger().warning("Attempted to initialize importer without first instantiating MagmaCore!");
            return;
        }
        new ConfigurationImporter(plugin);
    }

    public static JavaPlugin getRegisteredPlugin(String pluginName) {
        return registeredPlugins.get(pluginName);
    }

    public static Collection<JavaPlugin> getRegisteredPlugins() {
        return Collections.unmodifiableCollection(registeredPlugins.values());
    }

    public static void startInitialization(JavaPlugin plugin,
                                           PluginInitializationConfig config,
                                           Consumer<PluginInitializationContext> asyncInitialization,
                                           Consumer<PluginInitializationContext> syncInitialization,
                                           Runnable onSuccess,
                                           Consumer<Throwable> onFailure) {
        PluginInitializationManager.run(plugin, config, asyncInitialization, syncInitialization, onSuccess, onFailure);
    }

    public static PluginInitializationState getInitializationState(String pluginName) {
        return PluginInitializationManager.getState(pluginName);
    }

    public static boolean isPluginReady(String pluginName) {
        return PluginInitializationManager.isPluginReady(pluginName);
    }

    public static void requestInitializationShutdown(JavaPlugin plugin) {
        PluginInitializationManager.requestShutdown(plugin);
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

    private void registerNightbreakLogin() {
        // 1) grab the server's CommandMap by reflection
        SimpleCommandMap commandMap = null;
        try {
            Field f = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            f.setAccessible(true);
            commandMap = (SimpleCommandMap) f.get(Bukkit.getServer());
        } catch (ReflectiveOperationException e) {
            requestingPlugin.getLogger().warning("Couldn't access CommandMap: " + e.getMessage());
            return;
        }

        // 2) check if /nightbreaklogin is already registered
        if (commandMap.getCommand("nightbreaklogin") != null) {
            requestingPlugin.getLogger().info("/nightbreaklogin is already registered, skipping.");
            return;
        }

        // 3) register the permission (if it doesn't exist in PManager)
        if (Bukkit.getPluginManager().getPermission("nightbreak.login") == null) {
            Permission perm = new Permission(
                    "nightbreak.login",
                    "Lets admins register their Nightbreak account token for DLC access.",
                    PermissionDefault.OP
            );
            Bukkit.getPluginManager().addPermission(perm);
        }

        // 4) register the command with case-insensitive alias
        Command wrapper = AdvancedCommand.toBukkitCommand(
                requestingPlugin,
                new NightbreakLoginCommand(requestingPlugin),
                "nightbreaklogin",
                List.of("nightbreaklogin")
        );
        commandMap.register(requestingPlugin.getName(), wrapper);

        Logger.info("Registered /nightbreaklogin command");
    }
}
