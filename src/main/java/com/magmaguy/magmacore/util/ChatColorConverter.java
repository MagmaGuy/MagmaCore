package com.magmaguy.magmacore.util;

import net.md_5.bungee.api.ChatColor;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for converting color codes in strings.
 * Supports:
 * - Legacy &amp; codes (&amp;a, &amp;b, &amp;c, etc.)
 * - Hex colors: &amp;#RRGGBB or &lt;#RRGGBB&gt;
 * - Gradients: &lt;gradient:#START:#END&gt;text&lt;/gradient&gt; or &lt;g:#START:#END&gt;text&lt;/g&gt;
 * - Multi-color gradients: &lt;gradient:#COLOR1:#COLOR2:#COLOR3&gt;text&lt;/gradient&gt;
 */
public class ChatColorConverter {

    // Pattern for hex colors: &#RRGGBB or <#RRGGBB>
    private static final Pattern HEX_PATTERN = Pattern.compile("&?#([A-Fa-f0-9]{6})");
    private static final Pattern HEX_TAG_PATTERN = Pattern.compile("<#([A-Fa-f0-9]{6})>");

    // Pattern for gradients: <gradient:#START:#END>text</gradient> or <g:#START:#END>text</g>
    // Using .*? (reluctant) instead of [^<]* to allow < characters in the text (e.g., role names like <Quest Giver>)
    private static final Pattern GRADIENT_PATTERN = Pattern.compile("<(?:gradient|g):(#[A-Fa-f0-9]{6}(?::#[A-Fa-f0-9]{6})+)>(.*?)</(?:gradient|g)>");

    // Pattern for rainbow: <rainbow>text</rainbow> or <r>text</r>
    // Using .*? (reluctant) instead of [^<]* to allow < characters in the text
    private static final Pattern RAINBOW_PATTERN = Pattern.compile("<(?:rainbow|r)(?::(\\d+))?>(.*?)</(?:rainbow|r)>");

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

        // Process gradients first (they contain text that shouldn't be processed separately)
        string = processGradients(string);

        // Process rainbow
        string = processRainbow(string);

        // Process hex color tags <#RRGGBB>
        string = processHexTags(string);

        // Process hex colors &#RRGGBB
        string = processHexColors(string);

        // Finally, process legacy & codes
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', string);
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

    /**
     * Processes gradient tags in a string.
     * Format: &lt;gradient:#RRGGBB:#RRGGBB&gt;text&lt;/gradient&gt; or &lt;g:#RRGGBB:#RRGGBB&gt;text&lt;/g&gt;
     * Supports multiple colors: &lt;gradient:#FF0000:#00FF00:#0000FF&gt;text&lt;/gradient&gt;
     */
    private static String processGradients(String string) {
        Matcher matcher = GRADIENT_PATTERN.matcher(string);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String colorsStr = matcher.group(1); // e.g., "#FF0000:#00FF00" or "#FF0000:#00FF00:#0000FF"
            String text = matcher.group(2);

            String[] colorHexes = colorsStr.split(":");
            Color[] colors = new Color[colorHexes.length];
            for (int i = 0; i < colorHexes.length; i++) {
                colors[i] = hexToColor(colorHexes[i]);
            }

            String gradientText = applyGradient(text, colors);
            matcher.appendReplacement(result, Matcher.quoteReplacement(gradientText));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Processes rainbow tags in a string.
     * Format: &lt;rainbow&gt;text&lt;/rainbow&gt; or &lt;r&gt;text&lt;/r&gt;
     * Optional saturation: &lt;rainbow:50&gt;text&lt;/rainbow&gt; (0-100)
     */
    private static String processRainbow(String string) {
        Matcher matcher = RAINBOW_PATTERN.matcher(string);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String saturationStr = matcher.group(1);
            String text = matcher.group(2);

            float saturation = 1.0f;
            if (saturationStr != null) {
                saturation = Math.max(0, Math.min(100, Integer.parseInt(saturationStr))) / 100f;
            }

            String rainbowText = applyRainbow(text, saturation);
            matcher.appendReplacement(result, Matcher.quoteReplacement(rainbowText));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Processes hex color tags: &lt;#RRGGBB&gt;
     */
    private static String processHexTags(String string) {
        Matcher matcher = HEX_TAG_PATTERN.matcher(string);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String hex = matcher.group(1);
            String replacement = hexToChatColor(hex);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Processes hex colors: &amp;#RRGGBB
     */
    private static String processHexColors(String string) {
        Matcher matcher = HEX_PATTERN.matcher(string);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String hex = matcher.group(1);
            String replacement = hexToChatColor(hex);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Applies a gradient to text using multiple colors.
     *
     * @param text   The text to apply the gradient to
     * @param colors Array of colors to interpolate between
     * @return The text with gradient colors applied
     */
    public static String applyGradient(String text, Color... colors) {
        if (text == null || text.isEmpty()) return "";
        if (colors == null || colors.length == 0) return text;
        if (colors.length == 1) {
            // Single color, just apply it to all text
            return hexToChatColor(colorToHex(colors[0])) + text;
        }

        StringBuilder result = new StringBuilder();
        int length = text.length();

        // Skip color codes in the text when calculating positions
        String strippedText = stripColorCodes(text);
        int strippedLength = strippedText.length();

        if (strippedLength == 0) return text;
        if (strippedLength == 1) {
            return hexToChatColor(colorToHex(colors[0])) + text;
        }

        int colorIndex = 0;
        int segmentLength = (colors.length > 1) ? strippedLength / (colors.length - 1) : strippedLength;

        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);

            // Skip existing color codes
            if (c == '&' && i + 1 < length) {
                char next = text.charAt(i + 1);
                if ("0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(next) != -1) {
                    result.append(c).append(next);
                    i++;
                    continue;
                }
            }

            // Calculate position in stripped text
            float position = (float) colorIndex / (strippedLength - 1);

            // Determine which two colors to interpolate between
            float scaledPosition = position * (colors.length - 1);
            int colorIdx1 = (int) scaledPosition;
            int colorIdx2 = Math.min(colorIdx1 + 1, colors.length - 1);
            float localRatio = scaledPosition - colorIdx1;

            Color interpolated = interpolateColor(colors[colorIdx1], colors[colorIdx2], localRatio);
            result.append(hexToChatColor(colorToHex(interpolated))).append(c);
            colorIndex++;
        }

        return result.toString();
    }

    /**
     * Applies rainbow colors to text.
     *
     * @param text       The text to colorize
     * @param saturation The saturation of the colors (0.0 to 1.0)
     * @return The text with rainbow colors applied
     */
    public static String applyRainbow(String text, float saturation) {
        if (text == null || text.isEmpty()) return "";

        StringBuilder result = new StringBuilder();
        String strippedText = stripColorCodes(text);
        int strippedLength = strippedText.length();

        if (strippedLength == 0) return text;

        int colorIndex = 0;
        int length = text.length();

        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);

            // Skip existing color codes
            if (c == '&' && i + 1 < length) {
                char next = text.charAt(i + 1);
                if ("0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(next) != -1) {
                    result.append(c).append(next);
                    i++;
                    continue;
                }
            }

            // Calculate hue based on position
            float hue = (float) colorIndex / strippedLength;
            Color color = Color.getHSBColor(hue, saturation, 1.0f);

            result.append(hexToChatColor(colorToHex(color))).append(c);
            colorIndex++;
        }

        return result.toString();
    }

    /**
     * Converts a hex color string to ChatColor format.
     *
     * @param hex The hex color (without #)
     * @return The ChatColor string
     */
    private static String hexToChatColor(String hex) {
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }

        try {
            // Use BungeeCord ChatColor for hex support (Spigot 1.16+)
            return ChatColor.of("#" + hex).toString();
        } catch (Exception e) {
            // Fallback for older versions - find nearest legacy color
            return findNearestLegacyColor(hexToColor("#" + hex)).toString();
        }
    }

    /**
     * Converts a hex string to Color.
     */
    private static Color hexToColor(String hex) {
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        return new Color(
                Integer.parseInt(hex.substring(0, 2), 16),
                Integer.parseInt(hex.substring(2, 4), 16),
                Integer.parseInt(hex.substring(4, 6), 16)
        );
    }

    /**
     * Converts a Color to hex string.
     */
    private static String colorToHex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    /**
     * Interpolates between two colors.
     *
     * @param color1 Starting color
     * @param color2 Ending color
     * @param ratio  Interpolation ratio (0.0 to 1.0)
     * @return The interpolated color
     */
    private static Color interpolateColor(Color color1, Color color2, float ratio) {
        ratio = Math.max(0, Math.min(1, ratio));
        int r = (int) (color1.getRed() + ratio * (color2.getRed() - color1.getRed()));
        int g = (int) (color1.getGreen() + ratio * (color2.getGreen() - color1.getGreen()));
        int b = (int) (color1.getBlue() + ratio * (color2.getBlue() - color1.getBlue()));
        return new Color(r, g, b);
    }

    /**
     * Strips color codes from text (for length calculation).
     */
    private static String stripColorCodes(String text) {
        if (text == null) return "";
        return text.replaceAll("&[0-9A-Fa-fK-Ok-oRr]", "")
                .replaceAll("§[0-9A-Fa-fK-Ok-oRr]", "");
    }

    /**
     * Finds the nearest legacy ChatColor to a given Color.
     * Used as fallback for servers that don't support hex colors.
     */
    private static org.bukkit.ChatColor findNearestLegacyColor(Color color) {
        org.bukkit.ChatColor nearest = org.bukkit.ChatColor.WHITE;
        double minDistance = Double.MAX_VALUE;

        // Legacy color RGB values
        int[][] legacyColors = {
                {0, 0, 0},       // BLACK
                {0, 0, 170},     // DARK_BLUE
                {0, 170, 0},     // DARK_GREEN
                {0, 170, 170},   // DARK_AQUA
                {170, 0, 0},     // DARK_RED
                {170, 0, 170},   // DARK_PURPLE
                {255, 170, 0},   // GOLD
                {170, 170, 170}, // GRAY
                {85, 85, 85},    // DARK_GRAY
                {85, 85, 255},   // BLUE
                {85, 255, 85},   // GREEN
                {85, 255, 255},  // AQUA
                {255, 85, 85},   // RED
                {255, 85, 255},  // LIGHT_PURPLE
                {255, 255, 85},  // YELLOW
                {255, 255, 255}  // WHITE
        };

        org.bukkit.ChatColor[] colors = {
                org.bukkit.ChatColor.BLACK,
                org.bukkit.ChatColor.DARK_BLUE,
                org.bukkit.ChatColor.DARK_GREEN,
                org.bukkit.ChatColor.DARK_AQUA,
                org.bukkit.ChatColor.DARK_RED,
                org.bukkit.ChatColor.DARK_PURPLE,
                org.bukkit.ChatColor.GOLD,
                org.bukkit.ChatColor.GRAY,
                org.bukkit.ChatColor.DARK_GRAY,
                org.bukkit.ChatColor.BLUE,
                org.bukkit.ChatColor.GREEN,
                org.bukkit.ChatColor.AQUA,
                org.bukkit.ChatColor.RED,
                org.bukkit.ChatColor.LIGHT_PURPLE,
                org.bukkit.ChatColor.YELLOW,
                org.bukkit.ChatColor.WHITE
        };

        for (int i = 0; i < legacyColors.length; i++) {
            double distance = Math.sqrt(
                    Math.pow(color.getRed() - legacyColors[i][0], 2) +
                            Math.pow(color.getGreen() - legacyColors[i][1], 2) +
                            Math.pow(color.getBlue() - legacyColors[i][2], 2)
            );
            if (distance < minDistance) {
                minDistance = distance;
                nearest = colors[i];
            }
        }

        return nearest;
    }
}
