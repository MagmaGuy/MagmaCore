package com.magmaguy.magmacore.util;

public class Round {
    private Round() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Rounds a number to a specified number of decimal places.
     *
     * @param value  The number to round.
     * @param places The number of decimal places to round to.
     * @return The rounded number.
     */
    public static double decimalPlaces(final double value, final int places) {
        final double number = Math.pow(10, places);
        return Math.round(value * number) / number;
    }

    public static float decimalPlaces(final float value, final int places) {
        final double number = Math.pow(10, places);
        return Math.round(value * number) / (float) number;
    }

    /**
     * Rounds a number to 4 decimal places.
     *
     * @param value The number to round.
     * @return The rounded number.
     */
    public static double fourDecimalPlaces(final double value) {
        return decimalPlaces(value, 4);
    }

    public static float fourDecimalPlaces(final float value) {
        return decimalPlaces(value, 4);
    }

    /**
     * Rounds a number to 2 decimal places.
     *
     * @param value The number to round.
     * @return The rounded number.
     */
    public static double twoDecimalPlaces(final double value) {
        return decimalPlaces(value, 2);
    }

    /**
     * Rounds a number to 1 decimal places.
     *
     * @param value The number to round.
     * @return The rounded number.
     */
    public static double oneDecimalPlace(final double value) {
        return decimalPlaces(value, 1);
    }

}
