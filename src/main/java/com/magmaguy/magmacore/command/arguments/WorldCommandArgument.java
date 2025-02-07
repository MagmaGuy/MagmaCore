package com.magmaguy.magmacore.command.arguments;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.generator.WorldInfo;

import java.util.ArrayList;
import java.util.List;

public class WorldCommandArgument extends ListStringCommandArgument {
    public WorldCommandArgument(List<String> validValues, String hint) {
        super(validValues, hint);
    }

    public WorldCommandArgument(String hint) {
        super(new ArrayList<>(), hint);
    }

    @Override
    public List<String> getSuggestions(CommandSender sender, String partialInput) {
        if (super.validValues.isEmpty())
            return Bukkit.getWorlds().stream().map(WorldInfo::getName).toList();
        return super.getSuggestions(sender,partialInput);
    }
}
