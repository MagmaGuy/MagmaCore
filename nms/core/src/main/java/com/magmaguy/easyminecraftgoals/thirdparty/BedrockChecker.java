package com.magmaguy.easyminecraftgoals.thirdparty;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.regex.Pattern;

/**
 * Detects whether a Bukkit Player is connected via Bedrock Edition.
 *
 * <p>Detection is layered so the Bedrock-specific packet branches in
 * EasyMinecraftGoals (PacketBundle, FakeTextImpl, etc.) always fire for actual
 * Bedrock players, even when Bukkit's plugin lookup is stale or Floodgate's own
 * registry is slow to populate.</p>
 *
 * <ol>
 *   <li><b>Tier 0 — Name pattern.</b> Floodgate's default Bedrock username
 *       convention is a single {@code .} prefix and a 4-digit numeric suffix
 *       ({@code .Foo1234}). Java player names can't start with a dot, so this
 *       pattern is unambiguous in practice. Works regardless of which third-party
 *       plugin is loaded.</li>
 *   <li><b>Tier 1 — Floodgate plugin</b> (case-insensitive {@code getPlugins()}
 *       scan so forks under non-canonical names still match), routed through
 *       {@link Floodgate#isBedrock(Player)} which itself has UUID-signature and
 *       name-pattern fallbacks.</li>
 *   <li><b>Tier 2 — Geyser-Spigot plugin</b>, same case-insensitive lookup,
 *       routed through {@link Geyser#isBedrock(Player)}.</li>
 * </ol>
 */
public class BedrockChecker {
    private BedrockChecker() {
    }

    private static final Pattern BEDROCK_NAME_PATTERN = Pattern.compile("^\\..*\\d{4}$");

    public static boolean isBedrock(Player player) {
        if (player == null) return false;
        String name = player.getName();
        if (name != null && BEDROCK_NAME_PATTERN.matcher(name).matches()) return true;
        Plugin floodgate = findPlugin("floodgate");
        if (floodgate != null && floodgate.isEnabled()) return Floodgate.isBedrock(player);
        Plugin geyser = findPlugin("geyser-spigot");
        if (geyser == null) geyser = findPlugin("geyser");
        if (geyser != null && geyser.isEnabled()) return Geyser.isBedrock(player);
        return false;
    }

    private static Plugin findPlugin(String needle) {
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.getName().equalsIgnoreCase(needle)) return plugin;
        }
        return null;
    }
}
