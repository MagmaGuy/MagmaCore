package com.magmaguy.magmacore.command.arguments;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A {@link ListStringCommandArgument} whose valid values are re-read on every use instead of
 * being snapshotted when the command is registered.
 *
 * <p>Use this for values backed by reloadable configs (bosses, items, wormholes, quests...).
 * A plain {@link ListStringCommandArgument} built from a config collection at registration
 * time goes stale as soon as content is imported or reloaded, which makes freshly added
 * entries fail command validation with "not recognized" until the next full restart.
 *
 * <p>Its suggestions can also follow the arguments typed before it: see
 * {@link #DynamicListStringCommandArgument(Supplier, Function, String)}.
 */
public class DynamicListStringCommandArgument extends ListStringCommandArgument {

    private final Supplier<List<String>> validValuesSupplier;
    // The values to suggest after these earlier arguments, or null to suggest every valid value.
    private final Function<List<String>, List<String>> suggestionsAfter;

    public DynamicListStringCommandArgument(Supplier<List<String>> validValuesSupplier, String hint) {
        this(validValuesSupplier, null, hint);
    }

    /**
     * An argument that suggests only the values that fit what was typed before it. {@code validValuesSupplier} still
     * gives every value the argument accepts. {@code suggestionsAfter} is given the values typed for the command's
     * earlier arguments, in order from the first one after the alias, and answers the values to suggest, or null to
     * suggest every valid value. After {@code /fmm petadmin spawn penguin} it is given {@code [spawn, penguin]}, and
     * so can suggest penguin colours only.
     */
    public DynamicListStringCommandArgument(Supplier<List<String>> validValuesSupplier,
                                            Function<List<String>, List<String>> suggestionsAfter, String hint) {
        super(new ArrayList<>(), hint);
        this.validValuesSupplier = validValuesSupplier;
        this.suggestionsAfter = suggestionsAfter;
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
        return suggest(values(), partialInput);
    }

    @Override
    public List<String> getSuggestions(CommandSender sender, String partialInput, List<String> earlierArguments) {
        List<String> fitting = suggestionsAfter == null ? null : suggestionsAfter.apply(earlierArguments);
        return suggest(fitting == null ? values() : fitting, partialInput);
    }

    private List<String> suggest(List<String> values, String partialInput) {
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
