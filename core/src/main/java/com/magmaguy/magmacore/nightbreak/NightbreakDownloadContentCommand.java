package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class NightbreakDownloadContentCommand<T extends NightbreakManagedContent> extends AdvancedCommand {
    private final JavaPlugin plugin;
    private final NightbreakPluginSpec pluginSpec;
    private final Supplier<List<T>> packagesSupplier;
    private final AtomicBoolean guard;
    private final Consumer<CommandSender> reloadAction;
    private final boolean updatesOnly;

    public NightbreakDownloadContentCommand(JavaPlugin plugin,
                                            NightbreakPluginSpec pluginSpec,
                                            Supplier<List<T>> packagesSupplier,
                                            Consumer<CommandSender> reloadAction,
                                            boolean updatesOnly) {
        this(plugin, pluginSpec, packagesSupplier, reloadAction, updatesOnly, pluginSpec.setupPermission());
    }

    public NightbreakDownloadContentCommand(JavaPlugin plugin,
                                            NightbreakPluginSpec pluginSpec,
                                            Supplier<List<T>> packagesSupplier,
                                            Consumer<CommandSender> reloadAction,
                                            boolean updatesOnly,
                                            String permission) {
        this(plugin, pluginSpec, packagesSupplier,
                NightbreakPluginStateRegistry.getBulkOperationGuard(plugin),
                reloadAction,
                updatesOnly,
                permission);
    }

    public NightbreakDownloadContentCommand(JavaPlugin plugin,
                                            NightbreakPluginSpec pluginSpec,
                                            Supplier<List<T>> packagesSupplier,
                                            AtomicBoolean guard,
                                            Consumer<CommandSender> reloadAction,
                                            boolean updatesOnly) {
        this(plugin, pluginSpec, packagesSupplier, guard, reloadAction, updatesOnly, pluginSpec.setupPermission());
    }

    public NightbreakDownloadContentCommand(JavaPlugin plugin,
                                            NightbreakPluginSpec pluginSpec,
                                            Supplier<List<T>> packagesSupplier,
                                            AtomicBoolean guard,
                                            Consumer<CommandSender> reloadAction,
                                            boolean updatesOnly,
                                            String permission) {
        super(updatesOnly ? List.of("updatecontent", "updateallcontent") : List.of("downloadallcontent"));
        this.plugin = plugin;
        this.pluginSpec = pluginSpec;
        this.packagesSupplier = packagesSupplier;
        this.guard = guard;
        this.reloadAction = reloadAction;
        this.updatesOnly = updatesOnly;
        setPermission(permission);
        setSenderType(SenderType.ANY);
        setDescription(updatesOnly
                ? "Downloads updates for outdated " + pluginSpec.displayName() + " content."
                : "Downloads all available " + pluginSpec.displayName() + " content.");
        setUsage("/" + pluginSpec.rootCommand() + " " + (updatesOnly ? "updatecontent" : "downloadallcontent"));
    }

    @Override
    public void execute(CommandData commandData) {
        NightbreakBulkDownloader.execute(plugin,
                pluginSpec.displayName(),
                commandData.getCommandSender(),
                packagesSupplier.get(),
                updatesOnly,
                guard,
                reloadAction);
    }
}
