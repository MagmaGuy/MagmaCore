package com.magmaguy.magmacore.command.arguments;

import org.bukkit.command.CommandSender;

import java.util.List;

public interface ICommandArgument {
    //Command hint, contextualizes what the expected input (such as <x coord> or <filename.yml>)
    String hint();

    // Whether the player's current input for this argument is valid
    boolean matchesInput(String input);

    //Get all literals possible for this command
    List<String> literals();

    // Provide tab-completion suggestions given the partial input
    List<String> getSuggestions(CommandSender sender, String partialInput);

    // Is this argument "literal" (fixed text) or something else?
    boolean isLiteral();

    // Possibly more methods like getName(), isOptional(), etc.
}