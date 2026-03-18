package com.magmaguy.magmacore.command.arguments;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;

public class EntityTypeCommandArgument extends ListStringCommandArgument {
    public EntityTypeCommandArgument(List<String> validValues, String hint) {
        super(validValues, hint);
    }

    public EntityTypeCommandArgument() {
        super(new ArrayList<>(), "");
    }

    @Override
    public List<String> getSuggestions(CommandSender sender, String partialInput) {
        if (super.validValues.isEmpty()) {
            List<String> validEntities = new ArrayList<>();
            for (EntityType value : EntityType.values()) {
                if (value.equals(EntityType.UNKNOWN)) continue;
                validEntities.add(value.getKey().getKey());
            }
            return validEntities;
        }
        return super.getSuggestions(sender, partialInput);
    }

    @Override
    public boolean matchesInput(String input) {
        try {
            EntityType.valueOf(input);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
