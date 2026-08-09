package com.magmaguy.magmacore.command.arguments;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * A {@link ListStringCommandArgument} whose valid values are re-read on every use instead of
 * being snapshotted when the command is registered.
 *
 * <p>Use this for values backed by reloadable configs (bosses, items, wormholes, quests...).
 * A plain {@link ListStringCommandArgument} built from a config collection at registration
 * time goes stale as soon as content is imported or reloaded, which makes freshly added
 * entries fail command validation with "not recognized" until the next full restart.
 */
public class DynamicListStringCommandArgument extends ListStringCommandArgument {

    private final Supplier<List<String>> validValuesSupplier;

    public DynamicListStringCommandArgument(Supplier<List<String>> validValuesSupplier, String hint) {
        super(new ArrayList<>(), hint);
        this.validValuesSupplier = validValuesSupplier;
    }

    private List<String> values() {
        List<String> values = validValuesSupplier.get();
        return values == null ? List.of() : values;
    }

    @Override
    public boolean matchesInput(String input) {
        return values().stream().anyMatch(value -> value.equalsIgnoreCase(input));
    }

    @Override
    public List<String> literals() {
        return values();
    }

    @Override
    public List<String> getSuggestions(CommandSender sender, String partialInput) {
        List<String> values = values();
        if (values.isEmpty()) {
            return partialInput.isEmpty() ? List.of(hint) : List.of();
        }
        String lower = partialInput.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT)
                        .startsWith(lower))
                .toList();
    }
}
