package com.magmaguy.magmacore.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for converting color codes in strings.
 * Supports:
 * - Legacy &amp; codes (&amp;a, &amp;b, &amp;c, etc.)
 * - Hex colors: &amp;#RRGGBB or &lt;#RRGGBB&gt;
 * - Gradients: &lt;gradient:#START:#END&gt;text&lt;/gradient&gt; or &lt;g:#START:#END&gt;text&lt;/g&gt;
 * - Multi-color gradients: &lt;gradient:#COLOR1:#COLOR2:#COLOR3&gt;text&lt;/gradient&gt;
 */
public class ChatColorConverter {

    private ChatColorConverter() {
    }

    /**
     * Converts a string with color codes to a colored string.
     * Handles legacy &amp; codes, hex colors, and gradients.
     *
     * @param string The string to convert
     * @return The converted string with color codes applied
     */
    public static String convert(String string) {
        if (string == null) return "";

        // Routed through the full MiniMessage parser (com.magmaguy.magmacore.util.minimessage):
        // it handles legacy & / §  codes, &#RRGGBB and the BungeeCord spread-hex form, the historical
        // <gradient>/<rainbow>/<#hex> tags AND the complete MiniMessage tag set, then serialises back
        // to a legacy §-coded string so every existing String sink (names, items, scoreboards, titles)
        // keeps working unchanged.
        return com.magmaguy.magmacore.util.minimessage.MiniMessageParser.toLegacy(string);
    }

    /**
     * Converts a list of strings with color codes.
     *
     * @param list The list to convert
     * @return The converted list
     */
    public static List<String> convert(List<?> list) {
        if (list == null) return new ArrayList<>();
        List<String> convertedList = new ArrayList<>();
        for (Object value : list)
            convertedList.add(convert(value + ""));
        return convertedList;
    }
}
