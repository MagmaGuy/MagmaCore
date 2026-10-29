package com.magmaguy.magmacore.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The text of an overhead health display, as EliteMobs draws it over its elites: a numeric row (current over max, or
 * a percentage) and rows of bars filling from the bottom row, green above 75%, yellow above 50%, red above 25% and dark
 * red below. A creature sturdier than its kind's base (health multiplier above 1) gets more bars, up to 20 in two
 * rows, and a frailer one fewer, unless the size is fixed. Lines come back colour-converted, top to bottom.
 */
public final class HealthBarText {
    private static final String BAR = "▌";
    private static final String COLOR_HIGH = "&a", COLOR_MEDIUM = "&e", COLOR_LOW = "&c", COLOR_CRITICAL = "&4",
            COLOR_EMPTY = "&8";
    private static final int BARS_PER_ROW = 10;
    private static final double THOUSAND = 1_000D, MILLION = 1_000_000D, BILLION = 1_000_000_000D,
            TRILLION = 1_000_000_000_000D;

    /** How many bars, how many to a row, and how many rows. */
    public record BarLayout(int totalBars, int barsPerRow, int rows) {
    }

    private HealthBarText() {
    }

    /** Bar rows top to bottom; the bottom row fills first. */
    public static List<String> barLines(double currentHealth, double maxHealth, double healthMultiplier, boolean fixedSize) {
        BarLayout layout = layout(healthMultiplier, fixedSize);
        double healthRatio = maxHealth <= 0 ? 0 : Math.max(0, Math.min(1, currentHealth / maxHealth));
        int filledBars = (int) Math.ceil(healthRatio * layout.totalBars());
        String filledColor = color(healthRatio * 100);
        int barsRemaining = filledBars;
        int totalBarsRemaining = layout.totalBars();
        List<String> lines = new ArrayList<>();
        for (int row = 0; row < layout.rows(); row++) {
            int rowSize = Math.min(layout.barsPerRow(), totalBarsRemaining);
            int filledInRow = Math.min(rowSize, barsRemaining);
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < filledInRow; index++) text.append(filledColor).append(BAR);
            for (int index = filledInRow; index < rowSize; index++) text.append(COLOR_EMPTY).append(BAR);
            lines.add(ChatColorConverter.convert(text.toString()));
            barsRemaining -= filledInRow;
            totalBarsRemaining -= rowSize;
        }
        Collections.reverse(lines);
        return lines;
    }

    /** The numeric row: current and max health in the bar's colour, bold, joined by separator. */
    public static String numericLine(double currentHealth, double maxHealth, String separator) {
        double healthRatio = maxHealth <= 0 ? 0 : currentHealth / maxHealth;
        String color = color(healthRatio * 100);
        return ChatColorConverter.convert(color + "&l" + number(currentHealth) + separator + color + "&l" + number(maxHealth));
    }

    /** The numeric row as a percentage, for scaled combat. */
    public static String percentLine(double currentHealth, double maxHealth) {
        double healthRatio = maxHealth <= 0 ? 0 : currentHealth / maxHealth;
        return ChatColorConverter.convert(color(healthRatio * 100) + "&l" + percentage(healthRatio));
    }

    /**
     * The bar layout: 10 bars in one row at the base, fewer below it, and above it 10 more for every twentyfold of
     * health multiplier, at most 20 in two rows.
     */
    public static BarLayout layout(double healthMultiplier, boolean fixedSize) {
        if (fixedSize || healthMultiplier <= 0) return new BarLayout(BARS_PER_ROW, BARS_PER_ROW, 1);
        if (healthMultiplier < 1) {
            int totalBars = Math.max(1, (int) Math.round(BARS_PER_ROW * healthMultiplier));
            return new BarLayout(totalBars, totalBars, 1);
        }
        if (healthMultiplier == 1) return new BarLayout(BARS_PER_ROW, BARS_PER_ROW, 1);
        double logScale = Math.log(healthMultiplier) / Math.log(20);
        int totalBars = Math.min((int) Math.ceil(BARS_PER_ROW + BARS_PER_ROW * logScale), 20);
        if (totalBars <= BARS_PER_ROW) return new BarLayout(totalBars, totalBars, 1);
        return new BarLayout(totalBars, (int) Math.ceil(totalBars / 2.0), 2);
    }

    /** The colour code for a health percentage. */
    public static String color(double healthPercent) {
        if (healthPercent > 75) return COLOR_HIGH;
        if (healthPercent > 50) return COLOR_MEDIUM;
        if (healthPercent > 25) return COLOR_LOW;
        return COLOR_CRITICAL;
    }

    /** A health number, shortened to K, M, B and T past a thousand. */
    public static String number(double value) {
        if (value < 0) return "-" + number(-value);
        if (value >= TRILLION) return Round.twoDecimalPlaces(value / TRILLION) + "T";
        if (value >= BILLION) return Round.twoDecimalPlaces(value / BILLION) + "B";
        if (value >= MILLION) return Round.twoDecimalPlaces(value / MILLION) + "M";
        if (value >= THOUSAND) return Round.twoDecimalPlaces(value / THOUSAND) + "K";
        return String.valueOf(Round.twoDecimalPlaces(value));
    }

    /** A health share as a percentage. */
    public static String percentage(double ratio) {
        double percent = Math.max(0, ratio) * 100;
        if (percent >= 99.95) return "100%";
        if (percent >= 10) return Math.round(percent) + "%";
        if (percent >= 1) return Round.twoDecimalPlaces(percent) + "%";
        return String.format(Locale.US, "%.3f%%", percent);
    }
}
