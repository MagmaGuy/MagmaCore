package com.magmaguy.easyminecraftgoals.v26.mind;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeMindServerBudgetTest {
    @Test
    void fixedInsertionOrderCannotPermanentlyStarveLaterActors() {
        NativeMindServerBudget budget = new NativeMindServerBudget();
        List<String> actors = List.of("first", "second", "third");
        List<String> admittedFirst = new ArrayList<>();

        for (int tick = 0; tick < 6; tick++) {
            admittedFirst.add(budget.fairOrder(actors).getFirst());
        }

        assertEquals(
                List.of("first", "second", "third", "first", "second", "third"),
                admittedFirst);
    }
}
