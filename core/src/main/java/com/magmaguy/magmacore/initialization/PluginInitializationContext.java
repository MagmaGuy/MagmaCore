package com.magmaguy.magmacore.initialization;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public class PluginInitializationContext {
    /**
     * Set -Dmagmacore.inittiming=true on the server's startup command to print how long every
     * initialization step took, per plugin, once that plugin finishes initializing.
     * <p>
     * Startup cost is otherwise invisible: steps run across async and main-thread phases and only
     * announce that they started, so working out where the seconds went means attaching a profiler
     * or sampling thread dumps by hand. This makes a regression obvious from a normal log.
     */
    private static final boolean TIMING_ENABLED = Boolean.getBoolean("magmacore.inittiming");

    private final JavaPlugin plugin;
    private final PluginInitializationConfig config;
    private final String ownerToken;
    private final BooleanSupplier shutdownRequested;
    private final List<String> stepTimings = new ArrayList<>();
    private final long startNanos = System.nanoTime();
    private String currentStep = null;
    private long currentStepNanos = 0L;

    PluginInitializationContext(JavaPlugin plugin,
                                PluginInitializationConfig config,
                                String ownerToken,
                                BooleanSupplier shutdownRequested) {
        this.plugin = plugin;
        this.config = config;
        this.ownerToken = ownerToken;
        this.shutdownRequested = shutdownRequested;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public PluginInitializationConfig getConfig() {
        return config;
    }

    public void step(String description) {
        if (shutdownRequested.getAsBoolean()) return;
        if (TIMING_ENABLED) {
            long now = System.nanoTime();
            if (currentStep != null)
                stepTimings.add(String.format("%6d ms  %s", (now - currentStepNanos) / 1_000_000, currentStep));
            currentStep = description;
            currentStepNanos = now;
        }
        PluginInitializationProgressBar.step(plugin, ownerToken, description);
    }

    /**
     * Closes off the final step and prints the per-step breakdown. No-op unless timing is enabled.
     */
    void reportTimings() {
        if (!TIMING_ENABLED) return;
        long now = System.nanoTime();
        if (currentStep != null) {
            stepTimings.add(String.format("%6d ms  %s", (now - currentStepNanos) / 1_000_000, currentStep));
            currentStep = null;
        }
        Logger.info("Initialization profile (total " + ((now - startNanos) / 1_000_000) + " ms):");
        for (String timing : stepTimings) Logger.info("  " + timing);
    }

    public void status(String description) {
        if (shutdownRequested.getAsBoolean()) return;
        PluginInitializationProgressBar.status(plugin, ownerToken, description);
    }

    public boolean isShutdownRequested() {
        return shutdownRequested.getAsBoolean();
    }
}
