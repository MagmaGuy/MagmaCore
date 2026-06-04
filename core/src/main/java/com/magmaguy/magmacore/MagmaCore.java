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

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class MagmaCore {
    @Getter
    private static MagmaCore instance;
    private static final Map<String, JavaPlugin> registeredPlugins = new HashMap<>();
    private static final Set<String> listenerRegistrations = new HashSet<>();
    private static boolean instanceProtectorRegistered = false;
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
        enableWorldProtections(plugin);
        Bukkit.getPluginManager().registerEvents(new MatchPlayer.MatchPlayerEvents(), plugin);
        Bukkit.getPluginManager().registerEvents(new MatchInstance.MatchInstanceEvents(), plugin);
        Bukkit.getPluginManager().registerEvents(new MatchInstanceWorld.MatchInstanceWorldEvents(), plugin);
    }

    public static void enableWorldProtections() {
        enableWorldProtections(instance.requestingPlugin);
    }

    /**
     * Registers {@link InstanceProtector}, the world-scoped protection listener
     * extracted from EliteMobs' dungeon system. Plugins that use the match
     * system get this automatically via {@link #enableMatchSystem}. Plugins
     * that just want protections for their own worlds (no full match system)
     * call this directly. Safe to call multiple times — only registers once.
     */
    public static void enableWorldProtections(JavaPlugin plugin) {
        if (instanceProtectorRegistered) return;
        Bukkit.getPluginManager().registerEvents(new InstanceProtector(), plugin);
        instanceProtectorRegistered = true;
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
        shutdownNMSAdapter();
        CommandManager.shutdown();
        CustomBiomeCompatibility.shutdown();
        MatchInstance.shutdown();
        InstanceProtector.shutdown();
        instanceProtectorRegistered = false;
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

    private static void shutdownNMSAdapter() {
        try {
            Class<?> nmsManagerClass = Class.forName("com.magmaguy.easyminecraftgoals.NMSManager");
            nmsManagerClass.getMethod("shutdown").invoke(null);
        } catch (ClassNotFoundException ignored) {
            // MagmaCore core can be used without the shaded NMS module on the classpath.
        } catch (ReflectiveOperationException e) {
            Logger.warn("Failed to shut down NMS adapter: " + e.getMessage());
        }
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

    /**
     * @return true if {@link #requestInitializationShutdown(JavaPlugin)} has been
     * called for this plugin. Long-running async work (HTTP, file I/O, content
     * mixing) should poll this between phases and exit early when true so the
     * plugin's onDisable can return without Bukkit nagging about un-shutdown
     * async tasks.
     */
    public static boolean isShutdownRequested(JavaPlugin plugin) {
        return PluginInitializationManager.isShutdownRequested(plugin);
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

    // ---------------------------------------------------------------
    // Shared resource-pack asset export
    // ---------------------------------------------------------------

    private static final String NB_RSP_RESOURCE_PATH = "nightbreak_rsp_defaults";
    private static final String NB_RSP_CHECKSUM_FILE = ".nb_rsp_checksum_v2";

    /**
     * Exports the shared {@code nightbreak_rsp_defaults/} tree from the
     * MagmaCore jar into {@code <host plugin's data folder>/resource_pack/}.
     * Idempotent: a {@code .nb_rsp_checksum} file alongside the export records
     * the jar's content checksum, and subsequent calls with a matching checksum
     * are no-ops. Safe to call on every {@code onEnable}.
     * <p>
     * Failures are logged as warnings rather than propagated — a host plugin
     * should not fail to enable just because asset export couldn't write to
     * disk.
     *
     * @param host the consuming plugin; its data folder is the export root.
     */
    public static void exportSharedAssets(JavaPlugin host) {
        if (host == null) return;
        try {
            Path targetPath = host.getDataFolder().toPath().resolve("resource_pack");
            Path checksumFile = targetPath.resolve(NB_RSP_CHECKSUM_FILE);

            if (!Files.isDirectory(targetPath)) {
                Files.createDirectories(targetPath);
            }

            String jarChecksum = calculateNbRspChecksum();
            if (jarChecksum == null) {
                Logger.warn("Could not calculate nightbreak_rsp_defaults checksum from MagmaCore jar!");
                return;
            }

            if (Files.exists(checksumFile)) {
                try {
                    String existingChecksum = Files.readString(checksumFile).trim();
                    if (existingChecksum.equals(jarChecksum)) {
                        return; // up to date — no log spam
                    }
                } catch (IOException ignored) {
                    // fall through to re-export
                }
            }

            copyNbRspResourceFolder(targetPath);
            Files.writeString(checksumFile, jarChecksum);
            Logger.info("Exported nightbreak_rsp_defaults to " + targetPath + " for plugin " + host.getName() + ".");
        } catch (Exception e) {
            Logger.warn("Failed to export nightbreak_rsp_defaults for " + host.getName() + ": " + e.getMessage());
        }
    }

    private static String calculateNbRspChecksum() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            URL resourceUrl = MagmaCore.class.getClassLoader().getResource(NB_RSP_RESOURCE_PATH);

            if (resourceUrl == null) {
                return null;
            }

            if (resourceUrl.getProtocol().equals("jar")) {
                JarURLConnection jarConnection = (JarURLConnection) resourceUrl.openConnection();
                try (JarFile jarFile = jarConnection.getJarFile()) {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (entry.getName().startsWith(NB_RSP_RESOURCE_PATH) && !entry.isDirectory()) {
                            digest.update(entry.getName().getBytes());
                            digest.update(Long.toString(entry.getSize()).getBytes());
                        }
                    }
                }
            } else {
                // Running from IDE / exploded classpath
                Path resourcePath = java.nio.file.Paths.get(resourceUrl.toURI());
                Files.walk(resourcePath)
                        .filter(Files::isRegularFile)
                        .sorted()
                        .forEach(path -> {
                            try {
                                digest.update(path.toString().getBytes());
                                digest.update(Long.toString(Files.size(path)).getBytes());
                            } catch (IOException ignored) {
                            }
                        });
            }

            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Logger.warn("Error calculating nightbreak_rsp_defaults checksum: " + e.getMessage());
            return null;
        }
    }

    private static void copyNbRspResourceFolder(Path targetPath) throws IOException {
        URL resourceUrl = MagmaCore.class.getClassLoader().getResource(NB_RSP_RESOURCE_PATH);

        if (resourceUrl == null) {
            Logger.warn("Resource folder not found in MagmaCore jar: " + NB_RSP_RESOURCE_PATH);
            return;
        }

        if (resourceUrl.getProtocol().equals("jar")) {
            JarURLConnection jarConnection = (JarURLConnection) resourceUrl.openConnection();
            try (JarFile jarFile = jarConnection.getJarFile()) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String entryName = entry.getName();

                    if (entryName.startsWith(NB_RSP_RESOURCE_PATH + "/")) {
                        String relativePath = entryName;
                        if (relativePath.isEmpty()) continue;

                        Path targetFile = targetPath.resolve(relativePath);

                        if (entry.isDirectory()) {
                            Files.createDirectories(targetFile);
                        } else {
                            Files.createDirectories(targetFile.getParent());
                            try (InputStream is = jarFile.getInputStream(entry)) {
                                Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    }
                }
            }
        } else {
            // Running from IDE — file-system copy
            try {
                Path sourcePath = java.nio.file.Paths.get(resourceUrl.toURI());
                Files.walk(sourcePath).forEach(sourceFile -> {
                    try {
                        Path targetFile = targetPath.resolve(NB_RSP_RESOURCE_PATH).resolve(sourcePath.relativize(sourceFile).toString());
                        if (Files.isDirectory(sourceFile)) {
                            Files.createDirectories(targetFile);
                        } else {
                            Files.createDirectories(targetFile.getParent());
                            Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (Exception e) {
                Logger.warn("Failed to copy nightbreak_rsp_defaults from IDE classpath: " + e.getMessage());
            }
        }
    }
}
