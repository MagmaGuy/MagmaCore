package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class NightbreakApplyPluginUpdateCommand extends AdvancedCommand {
    private final JavaPlugin plugin;

    public NightbreakApplyPluginUpdateCommand(JavaPlugin plugin, NightbreakPluginSpec pluginSpec) {
        super(List.of("applypluginupdate"));
        this.plugin = plugin;
        setPermission(pluginSpec.adminPermission());
        setSenderType(SenderType.ANY);
        setDescription("Applies a downloaded " + pluginSpec.displayName() + " plugin update without a restart.");
        setUsage("/" + pluginSpec.rootCommand() + " applypluginupdate");
    }

    @Override
    public void execute(CommandData commandData) {
        NightbreakPluginHotSwap.applyStagedUpdate(plugin, commandData.getCommandSender());
    }
}
