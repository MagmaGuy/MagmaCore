package com.magmaguy.magmacore.command.arguments;

import java.util.ArrayList;
import java.util.List;

public class DoubleCommandArgument extends ListStringCommandArgument {
    public DoubleCommandArgument(List<Double> validValues, String hint) {
        super(validValues.stream().map(String::valueOf).toList(), hint);
    }

    public DoubleCommandArgument(String hint) {
        super(new ArrayList<>(), hint);
    }

    @Override
    public boolean matchesInput(String input) {
        try {
            Double.parseDouble(input);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
