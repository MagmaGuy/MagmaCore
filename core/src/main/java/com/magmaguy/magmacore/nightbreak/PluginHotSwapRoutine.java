package com.magmaguy.magmacore.nightbreak;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Replaces a running plugin with the jar staged in Bukkit's update folder,
 * cycling the plugins that depend on it.
 *
 * <p>{@link NightbreakPluginHotSwap} loads this class from a copy of its bytecode
 * through a separate class loader whose parent is the server's, because disabling
 * the target closes the class loader of the plugin that scheduled the swap. Once
 * that happens, the old plugin can no longer load classes from its jar. This class
 * therefore may only reference JDK and Bukkit types: no lambdas, nested classes,
 * enums, or other MagmaCore classes.</p>
 */
public final class PluginHotSwapRoutine implements Runnable {
    static final String ACTIVE_PROPERTY = "nightbreak.pluginHotSwap.active";

    private final String targetName;
    private final String[] unloadOrder;
    private final String[] loadOrder;
    private final File[] loadFiles;
    private final File stagedJar;
    private final File workDirectory;
    private final File routineDirectory;
    private final CommandSender sender;

    public PluginHotSwapRoutine(String targetName,
                                String[] unloadOrder,
                                String[] loadOrder,
                                File[] loadFiles,
                                File stagedJar,
                                File workDirectory,
                                File routineDirectory,
                                CommandSender sender) {
        this.targetName = targetName;
        this.unloadOrder = unloadOrder;
        this.loadOrder = loadOrder;
        this.loadFiles = loadFiles;
        this.stagedJar = stagedJar;
        this.workDirectory = workDirectory;
        this.routineDirectory = routineDirectory;
        this.sender = sender;
    }

    @Override
    public void run() {
        try {
            swap();
        } catch (Throwable throwable) {
            Bukkit.getLogger().log(Level.SEVERE, "[" + targetName + "] Plugin hot swap failed unexpectedly. "
                    + "Restart the server to recover.", throwable);
            report("§cThe hot swap failed unexpectedly. Restart the server to recover. Details are in the console.");
        } finally {
            System.clearProperty(ACTIVE_PROPERTY);
            deleteRecursively(routineDirectory);
            // Only succeeds when empty, so a backup kept after a failed restore stays.
            workDirectory.delete();
            if (getClass().getClassLoader() instanceof Closeable closeable) {
                try {
                    closeable.close();
                } catch (IOException ignored) {
                    // The class is already loaded; nothing else reads from this loader.
                }
            }
        }
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private void swap() {
        long started = System.nanoTime();
        PluginManager pluginManager = Bukkit.getPluginManager();
        File targetJar = loadFiles[0];
        File incomingJar = new File(workDirectory, targetJar.getName() + ".incoming");
        File previousJar = new File(workDirectory, targetJar.getName() + ".previous");
        Plugin running = pluginManager.getPlugin(targetName);
        String fromVersion = running == null ? "?" : running.getDescription().getVersion();

        for (String name : unloadOrder) {
            Plugin plugin = pluginManager.getPlugin(name);
            if (plugin != null) unload(pluginManager, plugin);
        }

        // Both servers apply a jar staged in the update folder whenever they load a
        // plugin, so the update leaves that folder before anything is loaded again.
        // Otherwise a rollback would load the failed update a second time.
        boolean taken = workDirectory.isDirectory() || workDirectory.mkdirs();
        taken = taken && moveQuietly(stagedJar, incomingJar);
        boolean replaced = taken
                && copyQuietly(targetJar, previousJar)
                && moveQuietly(incomingJar, targetJar);

        Plugin swapped = replaced ? load(pluginManager, targetJar) : null;
        boolean updated = swapped != null && swapped.isEnabled();
        if (!updated) {
            Plugin stray = pluginManager.getPlugin(targetName);
            if (stray != null) unload(pluginManager, stray);
            boolean restored = !replaced
                    || (moveQuietly(targetJar, incomingJar) && moveQuietly(previousJar, targetJar));
            swapped = restored ? load(pluginManager, targetJar) : null;
            // Leave the update staged so a restart behaves as it did before the swap.
            if (incomingJar.isFile()) moveQuietly(incomingJar, stagedJar);
            if (!restored) {
                Bukkit.getLogger().severe("[" + targetName + "] The previous jar is at " + previousJar.getPath()
                        + ". Copy it to " + targetJar.getPath() + " before restarting.");
            }
        }

        for (int i = 1; i < loadOrder.length; i++) {
            Plugin dependent = load(pluginManager, loadFiles[i]);
            if (dependent == null || !dependent.isEnabled()) {
                report("§c" + loadOrder[i] + " did not enable again after the swap. Check the console.");
            }
        }
        syncCommands();

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        String cycled = loadOrder.length > 1 ? " Also reloaded: " + join(loadOrder, 1) + "." : "";
        boolean enabled = swapped != null && swapped.isEnabled();
        if (enabled) previousJar.delete();
        if (updated) {
            report("§a" + targetName + " updated from " + fromVersion + " to "
                    + swapped.getDescription().getVersion() + " without a restart in " + elapsedMillis + " ms." + cycled);
        } else if (enabled) {
            report("§c" + targetName + " could not be updated without a restart, so " + fromVersion
                    + " was loaded again. The update stays staged for the next restart. Check the console for the cause."
                    + cycled);
        } else {
            report("§c" + targetName + " could not be updated or restored. Restart the server. "
                    + "Check the console for the cause." + cycled);
        }
    }

    private Plugin load(PluginManager pluginManager, File jar) {
        try {
            Plugin plugin = pluginManager.loadPlugin(jar);
            if (plugin == null) {
                Bukkit.getLogger().warning("[" + targetName + "] The server returned no plugin for " + jar.getName() + ".");
                return null;
            }
            // Paper's runtime loader calls onLoad itself; Spigot's leaves it to the caller.
            if (paperPluginManager(pluginManager) == null) plugin.onLoad();
            pluginManager.enablePlugin(plugin);
            return plugin;
        } catch (Throwable throwable) {
            Bukkit.getLogger().log(Level.SEVERE, "[" + targetName + "] Could not load " + jar.getName() + ".", throwable);
            return null;
        }
    }

    private void unload(PluginManager pluginManager, Plugin plugin) {
        try {
            if (plugin.isEnabled()) pluginManager.disablePlugin(plugin);
        } catch (Throwable throwable) {
            Bukkit.getLogger().log(Level.WARNING, "[" + targetName + "] Error while disabling " + plugin.getName() + ".",
                    throwable);
        }

        // Neither Spigot nor Paper removes a disabled plugin from its registry.
        removeRegistration(pluginManager, plugin);
        Object paperManager = paperPluginManager(pluginManager);
        if (paperManager != null) {
            Object instanceManager = readField(paperManager, "instanceManager");
            if (instanceManager != null) {
                removeRegistration(instanceManager, plugin);
                removeFromDependencyTree(readField(instanceManager, "dependencyTree"), plugin);
            }
            removeLaunchProviders(plugin);
        }

        removeCommands(plugin);
        removeHelpTopics(plugin);
        for (Permission permission : plugin.getDescription().getPermissions()) {
            pluginManager.removePermission(permission);
        }

        // Both servers close the class loader on disable; this covers servers that do not.
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        if (classLoader instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Already closed by the server.
            }
        }
    }

    @SuppressWarnings({"rawtypes"})
    private void removeRegistration(Object holder, Plugin plugin) {
        Object plugins = readField(holder, "plugins");
        if (plugins instanceof List list) list.remove(plugin);
        Object lookupNames = readField(holder, "lookupNames");
        if (lookupNames instanceof Map map) {
            Iterator iterator = map.values().iterator();
            while (iterator.hasNext()) {
                if (iterator.next() == plugin) iterator.remove();
            }
        }
    }

    private void removeFromDependencyTree(Object dependencyTree, Plugin plugin) {
        if (dependencyTree == null) return;
        try {
            Object meta = plugin.getClass().getMethod("getPluginMeta").invoke(plugin);
            for (Method method : dependencyTree.getClass().getMethods()) {
                if (!method.getName().equals("remove") || method.getParameterCount() != 1) continue;
                if (!method.getParameterTypes()[0].isInstance(meta)) continue;
                method.invoke(dependencyTree, meta);
                return;
            }
        } catch (ReflectiveOperationException exception) {
            Bukkit.getLogger().log(Level.WARNING, "[" + targetName + "] Could not remove " + plugin.getName()
                    + " from Paper's dependency tree.", exception);
        }
    }

    /**
     * Paper also records every runtime-loaded plugin in its launch provider storage,
     * which /plugins lists. A stale entry shows the plugin twice and keeps the old
     * class loader reachable.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void removeLaunchProviders(Plugin plugin) {
        try {
            ClassLoader serverClassLoader = Bukkit.getServer().getClass().getClassLoader();
            Class<?> handlerClass = Class.forName(
                    "io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler", false, serverClassLoader);
            Method getMeta = Class.forName(
                    "io.papermc.paper.plugin.provider.PluginProvider", false, serverClassLoader).getMethod("getMeta");
            Method getName = Class.forName(
                    "io.papermc.paper.plugin.configuration.PluginMeta", false, serverClassLoader).getMethod("getName");
            Object storage = readField(handlerClass.getField("INSTANCE").get(null), "storage");
            if (!(storage instanceof Map map)) return;
            for (Object providerStorage : map.values()) {
                if (!(readField(providerStorage, "providers") instanceof List providers)) continue;
                List stale = new ArrayList();
                for (Object provider : providers) {
                    Object meta = getMeta.invoke(provider);
                    if (meta != null && plugin.getName().equals(getName.invoke(meta))) stale.add(provider);
                }
                providers.removeAll(stale);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Bukkit.getLogger().log(Level.WARNING, "[" + targetName + "] Could not remove " + plugin.getName()
                    + " from Paper's launch provider storage.", exception);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void removeCommands(Plugin plugin) {
        try {
            Object server = Bukkit.getServer();
            CommandMap commandMap = (CommandMap) server.getClass().getMethod("getCommandMap").invoke(server);
            Object knownCommands = readField(commandMap, "knownCommands");
            if (!(knownCommands instanceof Map map)) return;
            ClassLoader classLoader = plugin.getClass().getClassLoader();
            List<Command> removed = new ArrayList<>();
            for (Object entryObject : new ArrayList(map.entrySet())) {
                Map.Entry entry = (Map.Entry) entryObject;
                if (!(entry.getValue() instanceof Command command) || !ownedBy(command, plugin, classLoader)) continue;
                map.remove(entry.getKey());
                if (!removed.contains(command)) removed.add(command);
            }
            for (Command command : removed) command.unregister(commandMap);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Bukkit.getLogger().log(Level.WARNING, "[" + targetName + "] Could not remove the commands of "
                    + plugin.getName() + ".", exception);
        }
    }

    /**
     * The help map is built once at startup and never pruned, so its topics keep
     * the boot-time plugin instance reachable through their commands.
     */
    @SuppressWarnings("rawtypes")
    private void removeHelpTopics(Plugin plugin) {
        Object topics = readField(Bukkit.getHelpMap(), "helpTopics");
        if (!(topics instanceof Map map)) return;
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        try {
            Iterator iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry entry = (Map.Entry) iterator.next();
                boolean pluginIndex = plugin.getName().equalsIgnoreCase(String.valueOf(entry.getKey()));
                Object command = readField(entry.getValue(), "command");
                if (pluginIndex || (command instanceof Command owned && ownedBy(owned, plugin, classLoader))) {
                    iterator.remove();
                }
            }
        } catch (RuntimeException exception) {
            Bukkit.getLogger().log(Level.WARNING, "[" + targetName + "] Could not remove the help topics of "
                    + plugin.getName() + ".", exception);
        }
    }

    private static boolean ownedBy(Command command, Plugin plugin, ClassLoader classLoader) {
        return command.getClass().getClassLoader() == classLoader
                || (command instanceof PluginIdentifiableCommand identifiable && identifiable.getPlugin() == plugin);
    }

    private void syncCommands() {
        try {
            Object server = Bukkit.getServer();
            server.getClass().getMethod("syncCommands").invoke(server);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Bukkit.getLogger().log(Level.WARNING, "[" + targetName + "] Could not resend the command tree.", exception);
        }
        try {
            // Adds topics for commands that have none, which after the unload means the reloaded plugins.
            Object helpMap = Bukkit.getHelpMap();
            helpMap.getClass().getMethod("initializeCommands").invoke(helpMap);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Bukkit.getLogger().log(Level.WARNING, "[" + targetName + "] Could not rebuild the help topics.", exception);
        }
        for (Player player : Bukkit.getOnlinePlayers()) player.updateCommands();
    }

    private static Object paperPluginManager(PluginManager pluginManager) {
        return readField(pluginManager, "paperPluginManager");
    }

    private static Object readField(Object holder, String name) {
        if (holder == null) return null;
        for (Class<?> type = holder.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(holder);
            } catch (NoSuchFieldException ignored) {
                // Keep walking up the hierarchy.
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return null;
            }
        }
        return null;
    }

    private boolean moveQuietly(File source, File target) {
        try {
            try {
                Files.move(source.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            Bukkit.getLogger().log(Level.WARNING, "[" + targetName + "] Could not move " + source.getPath()
                    + " to " + target.getPath() + ".", exception);
            return false;
        }
    }

    private boolean copyQuietly(File source, File target) {
        try {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException exception) {
            Bukkit.getLogger().log(Level.WARNING, "[" + targetName + "] Could not copy " + source.getPath()
                    + " to " + target.getPath() + ".", exception);
            return false;
        }
    }

    private static String join(String[] values, int from) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < values.length; i++) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(values[i]);
        }
        return builder.toString();
    }

    private void report(String message) {
        Bukkit.getLogger().info("[" + targetName + "] " + message.replaceAll("§.", ""));
        if (sender != null && !(sender instanceof ConsoleCommandSender)) sender.sendMessage(message);
    }
}
