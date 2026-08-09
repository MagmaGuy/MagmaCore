package com.magmaguy.magmacore.command.arguments;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the reason {@link DynamicListStringCommandArgument} exists: a plain
 * {@link ListStringCommandArgument} built from a config collection snapshots the values at command
 * registration, so content imported or reloaded afterwards fails validation with "not recognized"
 * until a full restart (the /em place wormhole defect). The dynamic variant must re-read the
 * supplier on every use.
 */
class DynamicListStringCommandArgumentTest {

    @Test
    void matchesInputReflectsValuesAddedAfterConstruction() {
        List<String> backing = new ArrayList<>();
        DynamicListStringCommandArgument argument =
                new DynamicListStringCommandArgument(() -> List.copyOf(backing), "<filename>");

        // Registration-time state: nothing loaded yet (e.g. commands registered before configs).
        assertFalse(argument.matchesInput("adventurers_guild_wormhole.yml"));

        // Content pack import / config reload adds the entry after the command already exists.
        backing.add("adventurers_guild_wormhole.yml");

        assertTrue(argument.matchesInput("adventurers_guild_wormhole.yml"),
                "values added after registration must validate without re-registering the command");
        assertTrue(argument.matchesInput("ADVENTURERS_GUILD_WORMHOLE.YML"),
                "matching must stay case-insensitive like the static argument");
        assertEquals(List.of("adventurers_guild_wormhole.yml"), argument.literals());
    }

    @Test
    void suggestionsFilterByPrefixAndFallBackToHint() {
        List<String> backing = new ArrayList<>();
        DynamicListStringCommandArgument argument =
                new DynamicListStringCommandArgument(() -> List.copyOf(backing), "<filename>");

        // Empty values + empty partial input -> show the hint, same contract as the static argument.
        assertEquals(List.of("<filename>"), argument.getSuggestions(null, ""));
        // Empty values + partial input -> nothing to suggest.
        assertEquals(List.of(), argument.getSuggestions(null, "adv"));

        backing.add("adventurers_guild_wormhole.yml");
        backing.add("primis_wormhole.yml");

        assertEquals(List.of("adventurers_guild_wormhole.yml"), argument.getSuggestions(null, "adv"));
        assertEquals(2, argument.getSuggestions(null, "").size());
    }

    @Test
    void nullSupplierResultBehavesAsEmptyInsteadOfThrowing() {
        DynamicListStringCommandArgument argument =
                new DynamicListStringCommandArgument(() -> null, "<filename>");

        assertFalse(argument.matchesInput("anything"));
        assertEquals(List.of(), argument.literals());
        assertEquals(List.of("<filename>"), argument.getSuggestions(null, ""));
    }
}
