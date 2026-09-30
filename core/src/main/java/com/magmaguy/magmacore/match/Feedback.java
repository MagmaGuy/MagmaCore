package com.magmaguy.magmacore.match;

import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.entity.Player;

/** Sends MatchMessages strings. A null template means the plugin asked for silence. */
final class Feedback {
    private Feedback() {
    }

    static void message(Player player, String template, Object... replacements) {
        if (template == null || player == null || !player.isOnline()) return;
        player.sendMessage(ChatColorConverter.convert(replace(template, replacements)));
    }

    static void title(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut,
                      Object... replacements) {
        if ((title == null && subtitle == null) || player == null || !player.isOnline()) return;
        player.sendTitle(
                title == null ? "" : ChatColorConverter.convert(replace(title, replacements)),
                subtitle == null ? "" : ChatColorConverter.convert(replace(subtitle, replacements)),
                fadeIn, stay, fadeOut);
    }

    /** Replacements come in pairs: placeholder, value. */
    private static String replace(String template, Object... replacements) {
        String result = template;
        for (int index = 0; index + 1 < replacements.length; index += 2)
            result = result.replace(String.valueOf(replacements[index]), String.valueOf(replacements[index + 1]));
        return result;
    }
}
