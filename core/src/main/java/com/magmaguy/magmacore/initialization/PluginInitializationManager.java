package com.magmaguy.magmacore.initialization;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class PluginInitializationManager {
    private static final Map<String, PluginInitializationState> pluginStates = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> shutdownRequests = new ConcurrentHashMap<>();

    private PluginInitializationManager() {
    }

    public static void run(JavaPlugin plugin,
                           PluginInitializationConfig config,
                           Consumer<PluginInitializationContext> asyncInitialization,
                           Consumer<PluginInitializationContext> syncInitialization,
                           Runnable onSuccess,
                           Consumer<Throwable> onFailure) {
        shutdownRequests.put(plugin.getName(), false);
        setState(plugin, PluginInitializationState.INITIALIZING);
        PluginInitializationProgressBar.start(plugin, config.displayName(), config.adminPermission(), config.totalSteps());
        PluginInitializationContext context = new PluginInitializationContext(plugin, config);

        List<String> presentDependencies = config.resolveDependencies(plugin).stream()
                .filter(PluginInitializationManager::isDependencyPresent)
                .toList();

        if (presentDependencies.isEmpty()) {
            runAsyncPhase(plugin, context, asyncInitialization, syncInitialization, onSuccess, onFailure);
            return;
        }

        context.status("Waiting for " + String.join(", ", presentDependencies));
        waitForDependencies(plugin, context, presentDependencies,
                () -> runAsyncPhase(plugin, context, asyncInitialization, syncInitialization, onSuccess, onFailure));
    }

    public static PluginInitializationState getState(String pluginName) {
        return pluginStates.getOrDefault(pluginName, PluginInitializationState.UNINITIALIZED);
    }

    public static boolean isPluginReady(String pluginName) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        if (plugin == null || !plugin.isEnabled()) {
            return true;
        }
        PluginInitializationState state = pluginStates.get(pluginName);
        if (state == null) {
            return true;
        }
        return state != PluginInitializationState.INITIALIZING;
    }

    public static boolean isShutdownRequested(JavaPlugin plugin) {
        return shutdownRequests.getOrDefault(plugin.getName(), false);
    }

    public static void requestShutdown(JavaPlugin plugin) {
        shutdownRequests.put(plugin.getName(), true);
    }

    public static void shutdown(JavaPlugin plugin) {
        shutdownRequests.put(plugin.getName(), true);
        PluginInitializationProgressBar.complete(plugin);
        setState(plugin, PluginInitializationState.UNINITIALIZED);
    }

    public static void setState(JavaPlugin plugin, PluginInitializationState state) {
        pluginStates.put(plugin.getName(), state);
    }

    private static boolean isDependencyPresent(String dependencyName) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(dependencyName);
        return plugin != null && plugin.isEnabled();
    }

    private static void waitForDependencies(JavaPlugin plugin,
                                            PluginInitializationContext context,
                                            List<String> dependencies,
                                            Runnable onReady) {
        new BukkitRunnable() {
            private String lastDescription = "";

            @Override
            public void run() {
                if (isShutdownRequested(plugin) || !plugin.isEnabled()) {
                    cancel();
                    return;
                }

                List<String> pendingDependencies = dependencies.stream()
                        .filter(dependency -> !isPluginReady(dependency))
                        .collect(Collectors.toList());

                if (pendingDependencies.isEmpty()) {
                    cancel();
                    onReady.run();
                    return;
                }

                String description = "Waiting for " + String.join(", ", pendingDependencies);
                if (!description.equals(lastDescription)) {
                    lastDescription = description;
                    context.status(description);
                }
            }
        }.runTaskTimerAsynchronously(plugin, 0L, 10L);
    }

    private static void runAsyncPhase(JavaPlugin plugin,
                                      PluginInitializationContext context,
                                      Consumer<PluginInitializationContext> asyncInitialization,
                                      Consumer<PluginInitializationContext> syncInitialization,
                                      Runnable onSuccess,
                                      Consumer<Throwable> onFailure) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                asyncInitialization.accept(context);
                if (isShutdownRequested(plugin)) {
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> runSyncPhase(plugin, context, syncInitialization, onSuccess, onFailure));
            } catch (Throwable throwable) {
                Bukkit.getScheduler().runTask(plugin, () -> fail(plugin, throwable, onFailure));
            }
        });
    }

    private static void runSyncPhase(JavaPlugin plugin,
                                     PluginInitializationContext context,
                                     Consumer<PluginInitializationContext> syncInitialization,
                                     Runnable onSuccess,
                                     Consumer<Throwable> onFailure) {
        try {
            if (isShutdownRequested(plugin)) {
                return;
            }
            syncInitialization.accept(context);
            if (isShutdownRequested(plugin)) {
                return;
            }
            PluginInitializationProgressBar.complete(plugin);
            setState(plugin, PluginInitializationState.INITIALIZED);
            onSuccess.run();
        } catch (Throwable throwable) {
            fail(plugin, throwable, onFailure);
        }
    }

    private static void fail(JavaPlugin plugin, Throwable throwable, Consumer<Throwable> onFailure) {
        PluginInitializationProgressBar.complete(plugin);
        setState(plugin, PluginInitializationState.FAILED);
        Logger.warn(plugin.getName() + " initialization failed!");
        onFailure.accept(throwable);
    }
}
