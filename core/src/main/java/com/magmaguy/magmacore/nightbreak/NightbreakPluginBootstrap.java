package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.CommandManager;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.initialization.PluginInitializationConfig;
import com.magmaguy.magmacore.initialization.PluginInitializationContext;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class NightbreakPluginBootstrap {
    private NightbreakPluginBootstrap() {
    }

    public static void startInitialization(JavaPlugin plugin,
                                           PluginInitializationConfig initializationConfig,
                                           NightbreakPluginSpec pluginSpec,
                                           NightbreakPluginHooks hooks) {
        MagmaCore.onEnable(plugin);
        MagmaCore.startInitialization(plugin,
                initializationConfig,
                context -> hooks.asyncInitialization(context),
                context -> hooks.syncInitialization(context),
                () -> {
                    hooks.onInitializationSuccess();
                    NightbreakPluginUpdater.autoDownloadPluginUpdateIfEnabled(plugin, pluginSpec);
                    CommandSender pendingReloadSender = NightbreakPluginStateRegistry.consumePendingReloadSender(plugin);
                    if (pendingReloadSender != null) {
                        Logger.sendMessage(pendingReloadSender, pluginSpec.reloadSuccessMessage());
                    }
                },
                throwable -> {
                    NightbreakPluginStateRegistry.clearPendingReloadSender(plugin);
                    hooks.onInitializationFailure(throwable);
                });
    }

    public static void setPendingReloadSender(JavaPlugin plugin, CommandSender sender) {
        NightbreakPluginStateRegistry.setPendingReloadSender(plugin, sender);
    }

    public static <T extends NightbreakManagedContent> void registerStandardCommands(JavaPlugin plugin,
                                                                                     CommandManager commandManager,
                                                                                     NightbreakPluginSpec pluginSpec,
                                                                                     Consumer<Player> setupAction,
                                                                                     Consumer<Player> initializeAction,
                                                                                     Supplier<List<T>> packagesSupplier,
                                                                                     Consumer<CommandSender> reloadAction) {
        AtomicBoolean guard = NightbreakPluginStateRegistry.getBulkOperationGuard(plugin);
        commandManager.registerCommand(new SetupCommand(pluginSpec, setupAction));
        if (pluginSpec.hasPresetModes())
            commandManager.registerCommand(new InitializeCommand(pluginSpec, initializeAction));
        commandManager.registerCommand(new NightbreakRecommendedPluginsCommand(plugin, pluginSpec));
        commandManager.registerCommand(new NightbreakDownloadPluginUpdateCommand(plugin, pluginSpec));
        commandManager.registerCommand(new NightbreakDownloadEverythingCommand<>(plugin, pluginSpec, packagesSupplier, guard, reloadAction));
        if (pluginSpec.hasContentPackages()) {
            commandManager.registerCommand(new NightbreakDownloadContentCommand<>(plugin, pluginSpec, packagesSupplier, guard, reloadAction, false));
            commandManager.registerCommand(new NightbreakDownloadContentCommand<>(plugin, pluginSpec, packagesSupplier, guard, reloadAction, true));
        }
    }

    /**
     * Simplified overload for content-less plugins that don't need initialize or download commands.
     */
    public static void registerStandardCommands(JavaPlugin plugin,
                                                CommandManager commandManager,
                                                NightbreakPluginSpec pluginSpec,
                                                Consumer<Player> setupAction,
                                                Consumer<CommandSender> reloadAction) {
        registerStandardCommands(plugin, commandManager, pluginSpec,
                setupAction, player -> {}, List::of, reloadAction);
    }

    private static final class SetupCommand extends AdvancedCommand {
        private final Consumer<Player> setupAction;

        private SetupCommand(NightbreakPluginSpec pluginSpec, Consumer<Player> setupAction) {
            super(List.of("setup"));
            this.setupAction = setupAction;
            setPermission(pluginSpec.setupPermission());
            setSenderType(SenderType.PLAYER);
            setDescription("Opens the setup menu for " + pluginSpec.displayName() + ".");
            setUsage("/" + pluginSpec.rootCommand() + " setup");
        }

        @Override
        public void execute(CommandData commandData) {
            setupAction.accept(commandData.getPlayerSender());
        }
    }

    private static final class InitializeCommand extends AdvancedCommand {
        private final Consumer<Player> initializeAction;

        private InitializeCommand(NightbreakPluginSpec pluginSpec, Consumer<Player> initializeAction) {
            super(List.of("initialize"));
            this.initializeAction = initializeAction;
            setPermission(pluginSpec.initializePermission());
            setSenderType(SenderType.PLAYER);
            setDescription("Opens the first-time setup menu for " + pluginSpec.displayName() + ".");
            setUsage("/" + pluginSpec.rootCommand() + " initialize");
        }

        @Override
        public void execute(CommandData commandData) {
            initializeAction.accept(commandData.getPlayerSender());
        }
    }

}
