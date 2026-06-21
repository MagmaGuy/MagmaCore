package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class NightbreakRecommendedPluginsCommand extends AdvancedCommand {
    private final JavaPlugin plugin;
    private final NightbreakPluginSpec spec;

    public NightbreakRecommendedPluginsCommand(JavaPlugin plugin, NightbreakPluginSpec spec) {
        super(List.of("recommendedplugins"));
        this.plugin = plugin;
        this.spec = spec;
        setPermission(spec.setupPermission());
        setSenderType(SenderType.ANY);
        setDescription("Shows recommended companion plugins for " + spec.displayName() + ".");
        setUsage("/" + spec.rootCommand() + " recommendedplugins");
    }

    @Override
    public void execute(CommandData commandData) {
        if (commandData.getCommandSender() instanceof Player player) {
            NightbreakCatalogMenu.openRecommendations(plugin, player, spec);
            return;
        }
        NightbreakCatalogMenu.sendRecommendations(commandData.getCommandSender(), spec);
    }
}
