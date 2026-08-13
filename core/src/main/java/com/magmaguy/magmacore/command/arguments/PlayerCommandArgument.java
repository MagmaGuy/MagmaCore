package com.magmaguy.magmacore.command.arguments;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class PlayerCommandArgument implements ICommandArgument {


    public PlayerCommandArgument() {
    }

    //This is never relevant when listing online players
    @Override
    public String hint() {
        return "";
    }

    @Override
    public boolean matchesInput(String input) {
        // Potentially always return true if you want any "player" to match
        // or actually check if input is an online player name.
        return getPlayerByName(input) != null;
    }

    @Override
    public List<String> literals() {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName).toList();
    }

    @Override
    public List<String> getSuggestions(CommandSender sender, String partialInput) {
        return Bukkit.getOnlinePlayers().stream()
                .filter(target -> !(sender instanceof Player viewer) || viewer.canSee(target))
                .map(Player::getName)
                .filter(name -> name.regionMatches(true, 0, partialInput, 0, partialInput.length()))
                .toList();
    }

    @Override
    public boolean isLiteral() {
        return false;
    }

    private Player getPlayerByName(String name) {
        return Bukkit.getPlayerExact(name);
    }
}
