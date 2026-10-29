package com.magmaguy.magmacore.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthBarTextTest {
    /** How many bars of a colour (an "&x" code) a converted line holds. */
    private static long count(String line, String colorCode) {
        String bar = "§" + colorCode.charAt(1) + "▌";
        return (line.length() - line.replace(bar, "").length()) / bar.length();
    }

    @Test
    void aBaseCreatureGetsTenBarsInOneRow() {
        assertEquals(new HealthBarText.BarLayout(10, 10, 1), HealthBarText.layout(1D, false));
        assertEquals(new HealthBarText.BarLayout(10, 10, 1), HealthBarText.layout(5D, true), "a fixed size ignores the multiplier");
    }

    @Test
    void sturdierCreaturesGetMoreBarsAndFrailerFewer() {
        assertEquals(new HealthBarText.BarLayout(5, 5, 1), HealthBarText.layout(0.5D, false));
        assertEquals(new HealthBarText.BarLayout(13, 7, 2), HealthBarText.layout(2D, false));
        assertEquals(new HealthBarText.BarLayout(20, 10, 2), HealthBarText.layout(20D, false));
        assertEquals(new HealthBarText.BarLayout(20, 10, 2), HealthBarText.layout(1000D, false), "never past 20");
    }

    @Test
    void barsFillFromTheBottomInTheHealthColour() {
        List<String> half = HealthBarText.barLines(10D, 20D, 1D, false);
        assertEquals(1, half.size());
        assertEquals(5, count(half.getFirst(), "&c"), "50% is red, five of ten");
        assertEquals(5, count(half.getFirst(), "&8"));
        List<String> twoRows = HealthBarText.barLines(30D, 40D, 2D, false);
        assertEquals(2, twoRows.size());
        // 75% of 13 bars is 10 filled: the bottom row's 7 and 3 of the top row's 6, in yellow.
        assertEquals(7, count(twoRows.get(1), "&e"));
        assertEquals(3, count(twoRows.get(0), "&e"));
    }

    @Test
    void theNumericRowReadsCurrentOverMax() {
        String line = HealthBarText.numericLine(18D, 20D, " &7/ ");
        assertTrue(line.contains("18.0") && line.contains("20.0") && line.startsWith("§a§l"), line);
        assertEquals("1.5K", HealthBarText.number(1500D));
        assertEquals("100%", HealthBarText.percentage(1D));
        assertEquals("&4", HealthBarText.color(10D));
    }
}
