package com.magmaguy.magmacore.command.arguments;

import lombok.Getter;
import org.bukkit.command.CommandSender;

import java.util.List;

public class LiteralCommandArgument implements ICommandArgument{
    @Getter
    private final String literal;

    public LiteralCommandArgument(String literal) {
        this.literal = literal;
    }

    //It's technically impossible to ever want to suggest this over just giving users the literal
    @Override
    public String hint() {
        return "";
    }

    @Override
    public boolean matchesInput(String input) {
        return literal.equalsIgnoreCase(input);
    }

    @Override
    public List<String> literals() {
        return List.of(literal);
    }

    @Override
    public List<String> getSuggestions(CommandSender sender, String partialInput) {
        // If partial input matches the beginning of the literal,
        // we can suggest the literal. Otherwise, no suggestions.
        if (literal.toLowerCase().startsWith(partialInput.toLowerCase())) {
            return List.of(literal);
        }
        return List.of();
    }

    @Override
    public boolean isLiteral() {
        return true;
    }
}
