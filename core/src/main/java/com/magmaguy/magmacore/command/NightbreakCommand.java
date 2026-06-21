package com.magmaguy.magmacore.command;

import com.magmaguy.magmacore.nightbreak.NightbreakCatalogMenu;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class NightbreakCommand extends AdvancedCommand {
    private final JavaPlugin plugin;

    public NightbreakCommand(JavaPlugin plugin) {
        super(new ArrayList<>());
        this.plugin = plugin;
        setUsage("/nightbreak plugins");
        setDescription("Shows the available plugin catalog.");
        setSenderType(SenderType.ANY);
        setPermission("nightbreak.plugins");
    }

    @Override
    public void execute(CommandData commandData) {
        String[] args = commandData.getArgs();
        if (args.length == 0 || !"plugins".equalsIgnoreCase(args[0])) {
            Logger.sendMessage(commandData.getCommandSender(), "&eUsage: /nightbreak plugins");
            return;
        }

        if (commandData.getCommandSender() instanceof Player player) {
            NightbreakCatalogMenu.openGlobalCatalog(plugin, player);
            return;
        }

        NightbreakCatalogMenu.sendGlobalCatalog(commandData.getCommandSender());
    }

    @Override
    public List<String> onTabComplete(String[] args) {
        if (args.length == 1 && "plugins".startsWith(args[0].toLowerCase())) {
            return List.of("plugins");
        }
        return List.of();
    }
}
