package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Applies a plugin update staged in Bukkit's update folder without a server
 * restart.
 *
 * <p>The swap disables the plugin and every MagmaGuy plugin that depends on it,
 * removes them from the server's plugin registry, replaces the jar, then loads
 * and enables them again. If the new jar does not enable, the previous jar is
 * loaded again and the update stays staged for the next restart.</p>
 *
 * <p>The swap itself runs in {@link PluginHotSwapRoutine}, loaded by a separate
 * class loader. See that class for why.</p>
 */
public final class NightbreakPluginHotSwap {
    public static final String APPLY_MODE_CONFIG_PATH = "nightbreak.applyPluginUpdatesWithoutRestart";
    public static final String MODE_NEVER = "never";
    public static final String MODE_WHEN_EMPTY = "when-empty";
    public static final String MODE_IMMEDIATELY = "immediately";
    static final List<String> APPLY_MODE_CONFIG_COMMENTS = List.of(
            "Applies a downloaded plugin update without restarting the server.",
            "never: wait for the next restart (default).",
            "when-empty: apply it the next time no players are online.",
            "immediately: apply it as soon as it is downloaded. Bosses, arenas and",
            "dungeon instances in progress end when the plugin reloads.",
            "Plugins from other authors that depend on this plugin block the swap.");
    private static final String WORK_DIRECTORY = ".nightbreak-hotswap";
    private static final String ROUTINE_DIRECTORY_PREFIX = "routine-";
    private static final String OWN_MAIN_CLASS_PREFIX = "com.magmaguy.";
    private static final long WHEN_EMPTY_CHECK_TICKS = 20L * 30L;
    private static final java.util.Map<String, String> RESTART_ONLY = new ConcurrentHashMap<>();

    private NightbreakPluginHotSwap() {
    }

    record Plan(String targetName,
                String runningVersion,
                String stagedVersion,
                File stagedJar,
                File workDirectory,
                List<String> loadOrder,
                List<File> loadFiles,
                List<File> heldBack) {
    }

    record PlanResult(Plan plan, String refusal) {
        static PlanResult refused(String refusal) {
            return new PlanResult(null, refusal);
        }
    }

    /**
     * Keeps this plugin's updates waiting for a restart, for example because part of
     * it runs outside Bukkit and cannot reload. Call before update checks start. The
     * plugin still reloads with its current jar when a plugin it depends on is swapped.
     */
    public static void requireRestartForUpdates(JavaPlugin plugin, String reason) {
        RESTART_ONLY.put(plugin.getName(), reason);
    }

    static boolean offersRestartFreeUpdates(JavaPlugin plugin) {
        return !RESTART_ONLY.containsKey(plugin.getName());
    }

    public static boolean hasStagedUpdate(JavaPlugin plugin) {
        File pluginJar = findPluginJar(plugin);
        return pluginJar != null && new File(NightbreakPluginUpdater.updateFolder(plugin), pluginJar.getName()).isFile();
    }

    /**
     * Schedules a swap to the staged update on the next tick. Must be called on
     * the server thread.
     *
     * @return true if the swap was scheduled
     */
    public static boolean applyStagedUpdate(JavaPlugin plugin, CommandSender sender) {
        PlanResult result = plan(plugin);
        if (result.plan() == null) {
            if (sender != null) Logger.sendSimpleMessage(sender, "&c" + result.refusal());
            return false;
        }
        Plan plan = result.plan();
        if (System.getProperty(PluginHotSwapRoutine.ACTIVE_PROPERTY) != null) {
            if (sender != null) Logger.sendSimpleMessage(sender, "&cAnother plugin swap is already in progress.");
            return false;
        }

        Runnable routine;
        try {
            routine = createRoutine(plan, sender);
        } catch (ReflectiveOperationException | IOException | RuntimeException exception) {
            plugin.getLogger().warning("Could not prepare the plugin swap: " + exception);
            if (sender != null) Logger.sendSimpleMessage(sender, "&cCould not prepare the plugin swap. Check the console.");
            return false;
        }

        System.setProperty(PluginHotSwapRoutine.ACTIVE_PROPERTY, plan.targetName());
        try {
            Bukkit.getScheduler().runTask(plugin, routine);
        } catch (RuntimeException exception) {
            System.clearProperty(PluginHotSwapRoutine.ACTIVE_PROPERTY);
            throw exception;
        }
        if (sender != null) {
            Logger.sendSimpleMessage(sender, "&7Applying " + plan.targetName() + " " + plan.stagedVersion()
                    + " over " + plan.runningVersion() + " without a restart...");
            if (plan.loadOrder().size() > 1) {
                Logger.sendSimpleMessage(sender, "&7These plugins depend on it and will reload too: &f"
                        + String.join(", ", plan.loadOrder().subList(1, plan.loadOrder().size())));
            }
        }
        return true;
    }

    /**
     * Applies a freshly downloaded update according to
     * {@link #APPLY_MODE_CONFIG_PATH}. Must be called on the server thread.
     *
     * @return true if the update was applied or deferred until the server is
     * empty; false if it waits for a restart
     */
    static boolean applyDownloadedUpdate(JavaPlugin plugin, CommandSender sender) {
        String mode = applyMode(plugin);
        if (MODE_NEVER.equals(mode)) return false;
        CommandSender recipient = sender == null ? Bukkit.getConsoleSender() : sender;
        if (MODE_IMMEDIATELY.equals(mode) || Bukkit.getOnlinePlayers().isEmpty()) {
            return applyStagedUpdate(plugin, recipient);
        }
        PlanResult result = plan(plugin);
        if (result.plan() == null) {
            Logger.sendSimpleMessage(recipient, "&c" + result.refusal());
            return false;
        }
        Logger.sendSimpleMessage(recipient, "&7The plugin update will be applied the next time no players are online.");
        // Owned by the running plugin, so a restart or swap cancels it.
        int[] taskId = {-1};
        taskId[0] = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (!Bukkit.getOnlinePlayers().isEmpty()) return;
            Bukkit.getScheduler().cancelTask(taskId[0]);
            applyStagedUpdate(plugin, Bukkit.getConsoleSender());
        }, WHEN_EMPTY_CHECK_TICKS, WHEN_EMPTY_CHECK_TICKS);
        return true;
    }

    static String applyMode(JavaPlugin plugin) {
        if (!offersRestartFreeUpdates(plugin)) return MODE_NEVER;
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) return MODE_NEVER;
        String mode = YamlConfiguration.loadConfiguration(configFile)
                .getString(APPLY_MODE_CONFIG_PATH, MODE_NEVER)
                .trim()
                .toLowerCase(Locale.ROOT);
        return switch (mode) {
            case MODE_WHEN_EMPTY, MODE_IMMEDIATELY -> mode;
            default -> MODE_NEVER;
        };
    }

    static PlanResult plan(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread()) {
            return PlanResult.refused("Plugin swaps must start on the server thread.");
        }
        String restartReason = RESTART_ONLY.get(plugin.getName());
        if (restartReason != null) {
            return PlanResult.refused(plugin.getName() + " updates need a server restart: " + restartReason);
        }
        File pluginJar = findPluginJar(plugin);
        if (pluginJar == null) {
            return PlanResult.refused("Could not find the jar " + plugin.getName() + " was loaded from.");
        }
        File stagedJar = new File(NightbreakPluginUpdater.updateFolder(plugin), pluginJar.getName());
        if (!stagedJar.isFile()) {
            return PlanResult.refused("No downloaded update is waiting for " + plugin.getName() + ".");
        }

        PluginDescriptionFile stagedDescription = readDescription(stagedJar);
        if (stagedDescription == null) {
            return PlanResult.refused("The staged update for " + plugin.getName() + " has no readable plugin.yml.");
        }
        DownloadedPluginJarValidator.ValidationResult validation = DownloadedPluginJarValidator.validate(
                stagedJar, List.of(plugin.getName()), stagedDescription.getVersion());
        if (!validation.valid()) {
            return PlanResult.refused("The staged update is not valid: " + validation.detail());
        }
        String runningVersion = plugin.getDescription().getVersion();
        if (NightbreakPluginUpdater.compareVersions(stagedDescription.getVersion(), runningVersion) <= 0) {
            return PlanResult.refused("The staged update (" + stagedDescription.getVersion()
                    + ") is not newer than the running version (" + runningVersion + ").");
        }

        // Every enabled plugin that depends on a member of the set links against
        // classes about to be replaced, so it has to reload with them.
        Plugin[] serverOrder = Bukkit.getPluginManager().getPlugins();
        Set<String> members = new LinkedHashSet<>();
        members.add(plugin.getName());
        List<String> foreignDependents = new ArrayList<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Plugin candidate : serverOrder) {
                if (!candidate.isEnabled() || members.contains(candidate.getName())) continue;
                if (!dependsOnAny(candidate, members)) continue;
                if (!candidate.getDescription().getMain().startsWith(OWN_MAIN_CLASS_PREFIX)) {
                    if (!foreignDependents.contains(candidate.getName())) foreignDependents.add(candidate.getName());
                    continue;
                }
                members.add(candidate.getName());
                grew = true;
            }
        }
        if (!foreignDependents.isEmpty()) {
            return PlanResult.refused("These plugins depend on " + plugin.getName()
                    + " and may not survive a reload: " + String.join(", ", foreignDependents)
                    + ". Restart the server to apply the update.");
        }

        // The server's plugin list is in load order, so dependents follow the target.
        List<String> loadOrder = new ArrayList<>();
        List<File> loadFiles = new ArrayList<>();
        loadOrder.add(plugin.getName());
        loadFiles.add(pluginJar);
        for (Plugin candidate : serverOrder) {
            if (candidate == plugin || !members.contains(candidate.getName())) continue;
            File candidateJar = candidate instanceof JavaPlugin javaPlugin ? findPluginJar(javaPlugin) : null;
            if (candidateJar == null) {
                return PlanResult.refused("Could not find the jar " + candidate.getName() + " was loaded from.");
            }
            loadOrder.add(candidate.getName());
            loadFiles.add(candidateJar);
        }

        // Loading a plugin also applies any update staged for it. Only the target may
        // change version, so every other staged jar for these plugins waits aside.
        List<File> heldBack = new ArrayList<>();
        File[] staged = stagedJar.getParentFile().listFiles();
        if (staged != null) {
            for (File file : staged) {
                if (file.equals(stagedJar) || !file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                PluginDescriptionFile description = readDescription(file);
                if (description != null && members.contains(description.getName())) heldBack.add(file);
            }
        }

        File workDirectory = new File(pluginJar.getParentFile(), WORK_DIRECTORY);
        return new PlanResult(new Plan(plugin.getName(),
                runningVersion,
                stagedDescription.getVersion(),
                stagedJar,
                workDirectory,
                List.copyOf(loadOrder),
                List.copyOf(loadFiles),
                List.copyOf(heldBack)), null);
    }

    private static boolean dependsOnAny(Plugin candidate, Set<String> names) {
        PluginDescriptionFile description = candidate.getDescription();
        for (String dependency : description.getDepend()) {
            if (names.contains(dependency)) return true;
        }
        for (String dependency : description.getSoftDepend()) {
            if (names.contains(dependency)) return true;
        }
        return false;
    }

    private static Runnable createRoutine(Plan plan, CommandSender sender)
            throws IOException, ReflectiveOperationException {
        String className = PluginHotSwapRoutine.class.getName();
        byte[] bytecode;
        try (InputStream input = NightbreakPluginHotSwap.class.getClassLoader()
                .getResourceAsStream(className.replace('.', '/') + ".class")) {
            if (input == null) throw new IOException("Missing " + className + " bytecode.");
            bytecode = input.readAllBytes();
        }
        deleteStaleRoutines(plan.workDirectory());
        Path routineDirectory = plan.workDirectory().toPath().resolve(ROUTINE_DIRECTORY_PREFIX + UUID.randomUUID());
        Path classFile = routineDirectory.resolve(className.replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytecode);

        // A JDK class loader, not one declared in this jar. Paper caches the caller of
        // defineClass from plugin code, and the new plugin keeps the stack it was loaded
        // from, so any class from this jar on that path would keep the old plugin loaded.
        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{routineDirectory.toUri().toURL()}, Bukkit.class.getClassLoader());
        Class<?> routineClass = Class.forName(className, true, classLoader);

        List<String> loadOrder = plan.loadOrder();
        String[] unloadOrder = new String[loadOrder.size()];
        for (int i = 0; i < loadOrder.size(); i++) {
            unloadOrder[i] = loadOrder.get(loadOrder.size() - 1 - i);
        }
        return (Runnable) routineClass.getConstructor(
                        String.class, String[].class, String[].class, File[].class,
                        File.class, File[].class, File.class, File.class, CommandSender.class)
                .newInstance(plan.targetName(),
                        unloadOrder,
                        loadOrder.toArray(new String[0]),
                        plan.loadFiles().toArray(new File[0]),
                        plan.stagedJar(),
                        plan.heldBack().toArray(new File[0]),
                        plan.workDirectory(),
                        routineDirectory.toFile(),
                        sender);
    }

    private static void deleteStaleRoutines(File workDirectory) {
        File[] entries = workDirectory.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory() && entry.getName().startsWith(ROUTINE_DIRECTORY_PREFIX)) {
                PluginHotSwapRoutine.deleteRecursively(entry);
            }
        }
    }

    /**
     * Finds the jar in the plugins folder that declares this plugin. On Paper the
     * code source can be a remapped copy, so the plugins folder is checked by the
     * code source's file name first and then by descriptor.
     */
    static File findPluginJar(JavaPlugin plugin) {
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        if (pluginsFolder == null) return null;
        String codeSourceName = codeSourceName(plugin);
        if (codeSourceName != null) {
            File candidate = new File(pluginsFolder, codeSourceName);
            if (declaresPlugin(candidate, plugin.getName())) return candidate;
        }
        File[] files = pluginsFolder.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.getName().toLowerCase(Locale.ROOT).endsWith(".jar") && declaresPlugin(file, plugin.getName())) {
                return file;
            }
        }
        return null;
    }

    private static String codeSourceName(JavaPlugin plugin) {
        try {
            URL location = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            return new File(location.toURI()).getName();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean declaresPlugin(File jar, String pluginName) {
        if (!jar.isFile()) return false;
        PluginDescriptionFile description = readDescription(jar);
        return description != null && description.getName().equals(pluginName);
    }

    private static PluginDescriptionFile readDescription(File jar) {
        try (JarFile jarFile = new JarFile(jar)) {
            JarEntry entry = jarFile.getJarEntry("plugin.yml");
            if (entry == null) return null;
            try (InputStream input = jarFile.getInputStream(entry)) {
                return new PluginDescriptionFile(input);
            }
        } catch (IOException | InvalidDescriptionException | RuntimeException exception) {
            return null;
        }
    }

}
