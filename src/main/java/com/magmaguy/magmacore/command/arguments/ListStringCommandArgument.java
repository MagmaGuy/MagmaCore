package com.magmaguy.magmacore.command.arguments;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

public class ListStringCommandArgument implements ICommandArgument {
    protected final List<String> validValues;
    protected String hint;

    public ListStringCommandArgument(List<String> validValues, String hint) {
        this.validValues = validValues;
        this.hint = hint;
    }

    public ListStringCommandArgument(String hint) {
        this.validValues = new ArrayList<>();
        this.hint = hint;
    }

    @Override
    public String hint() {
        return hint;
    }

    @Override
    public boolean matchesInput(String input) {
        return validValues.stream().anyMatch(value -> value.equalsIgnoreCase(input));
    }

    @Override
    public List<String> literals() {
        return validValues;
    }

    @Override
    public List<String> getSuggestions(CommandSender sender, String partialInput) {
        if (validValues.isEmpty()) {
            // Show hint only when the user hasn't typed anything yet
            return partialInput.isEmpty() ? List.of(hint) : List.of();
        }

        // Existing filtering logic for predefined values
        String lower = partialInput.toLowerCase();
        return validValues.stream()
                .filter(value -> value.toLowerCase().startsWith(lower))
                .toList();
    }

    @Override
    public boolean isLiteral() {
        return false;
    }
}
