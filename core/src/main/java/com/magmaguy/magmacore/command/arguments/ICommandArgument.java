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

    /**
     * Whether this argument may be omitted by the user. Optional arguments
     * must be trailing (you can't have a required arg after an optional one).
     * Default is {@code false} — implementations don't have to override.
     *
     * <p>{@link com.magmaguy.magmacore.command.AdvancedCommand#addOptionalArgument}
     * wraps any {@code ICommandArgument} in an internal decorator that
     * flips this flag, so callers usually don't implement it directly.
     */
    default boolean isOptional() {
        return false;
    }
}