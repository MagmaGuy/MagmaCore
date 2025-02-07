package com.magmaguy.magmacore.command.arguments;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

public class IntegerCommandArgument extends ListStringCommandArgument {
    public IntegerCommandArgument(List<Integer> validValues, String hint) {
        super(validValues.stream().map(String::valueOf).toList(), hint);
    }

    public IntegerCommandArgument(String hint) {
        super(new ArrayList<>(), hint);
    }

    @Override
    public boolean matchesInput(String input) {
        try {
            Integer.parseInt(input);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

}
