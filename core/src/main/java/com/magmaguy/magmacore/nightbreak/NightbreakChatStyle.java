package com.magmaguy.magmacore.nightbreak;

import org.bukkit.configuration.file.FileConfiguration;

/** Shared chat decorations and migration of their previous stock configuration values. */
public final class NightbreakChatStyle {
    private static final String GRADIENT = "<g:#8B0000:#CC4400:#DAA520>";
    private static final String SEPARATOR = GRADIENT + "▬".repeat(71) + "</g>";
    private static final String LEGACY_SEPARATOR = GRADIENT + "▬".repeat(80) + "</g>";

    private NightbreakChatStyle() { }

    public static String separator() {
        return SEPARATOR;
    }

    /** Replaces only the old stock value, leaving custom colors, widths and text untouched. */
    public static void migrateSeparators(FileConfiguration configuration, String... keys) {
        for (String key : keys)
            if (LEGACY_SEPARATOR.equals(configuration.getString(key)))
                configuration.set(key, SEPARATOR);
    }
}
