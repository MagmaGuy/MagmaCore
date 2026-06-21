package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class NightbreakDownloadPluginUpdateCommand extends AdvancedCommand {
    private final JavaPlugin plugin;
    private final NightbreakPluginSpec pluginSpec;

    public NightbreakDownloadPluginUpdateCommand(JavaPlugin plugin, NightbreakPluginSpec pluginSpec) {
        super(List.of("downloadpluginupdate"));
        this.plugin = plugin;
        this.pluginSpec = pluginSpec;
        setPermission(pluginSpec.adminPermission());
        setSenderType(SenderType.ANY);
        setDescription("Downloads a " + pluginSpec.displayName() + " plugin update.");
        setUsage("/" + pluginSpec.rootCommand() + " downloadpluginupdate");
    }

    @Override
    public void execute(CommandData commandData) {
        NightbreakPluginUpdater.downloadPluginUpdateAsync(plugin, pluginSpec, commandData.getCommandSender(), null);
    }
}
