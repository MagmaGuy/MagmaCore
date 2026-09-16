package com.magmaguy.easyminecraftgoals.thirdparty;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.regex.Pattern;

/**
 * Shared Bukkit-side Bedrock detection for presentation, packets and pack delivery.
 * Floodgate's synthetic UUID survives proxy forwarding without a local Floodgate
 * plugin. The existing dotted-name heuristic is also retained, but a numeric suffix
 * is not required by Floodgate and is not the primary identity signal.
 * Local APIs additionally identify linked accounts with ordinary Java UUIDs.
 * These presentation heuristics must not be used for authentication or permissions.
 */
public class BedrockChecker {
    private BedrockChecker() {
    }

    private static final Pattern BEDROCK_NAME_PATTERN = Pattern.compile("^\\..*\\d{4}$");

    public static boolean isBedrock(Player player) {
        if (player == null) return false;
        if (player.getUniqueId().getMostSignificantBits() == 0L) return true;
        String name = player.getName();
        if (name != null && BEDROCK_NAME_PATTERN.matcher(name).matches()) return true;
        Plugin floodgate = findPlugin("floodgate");
        if (floodgate != null && Floodgate.isBedrock(player)) return true;
        return findGeyser() != null && Geyser.isBedrock(player);
    }

    /** Local API availability, not proof that a proxy cannot supply Bedrock viewers. */
    public static boolean isBedrockSupportPresent() {
        return findPlugin("floodgate") != null || findGeyser() != null;
    }

    private static Plugin findGeyser() {
        Plugin plugin = findPlugin("Geyser-Spigot");
        if (plugin == null) plugin = findPlugin("Geyser-Bukkit");
        return plugin != null ? plugin : findPlugin("Geyser");
    }

    private static Plugin findPlugin(String needle) {
        Plugin exact = Bukkit.getPluginManager().getPlugin(needle);
        if (exact != null && exact.isEnabled()) return exact;
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.isEnabled() && plugin.getName().equalsIgnoreCase(needle)) return plugin;
        }
        return null;
    }
}
