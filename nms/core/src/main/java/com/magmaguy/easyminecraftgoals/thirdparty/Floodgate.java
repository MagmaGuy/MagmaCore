package com.magmaguy.easyminecraftgoals.thirdparty;

import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Floodgate integration for Bedrock player detection.
 *
 * <p>Three-tier detection so a Floodgate hiccup or stale player registry can't
 * misroute a Bedrock viewer through the Java packet path:</p>
 * <ol>
 *   <li>{@link FloodgateApi#isFloodgatePlayer(UUID)} — Floodgate's own answer.</li>
 *   <li>UUID-signature check — Floodgate-generated Bedrock UUIDs always have
 *       {@code MSB == 0} (the XUID is packed into the low 64 bits); real Java
 *       v4 UUIDs never do.</li>
 *   <li>Username pattern — Floodgate's default Bedrock username is a single
 *       {@code .} prefix and a 4-digit numeric suffix.</li>
 * </ol>
 */
class Floodgate {
    private Floodgate() {
    }

    private static final Pattern BEDROCK_NAME_PATTERN = Pattern.compile("^\\..*\\d{4}$");

    static boolean isBedrock(Player player) {
        if (player == null) return false;
        UUID uuid = player.getUniqueId();
        try {
            if (FloodgateApi.getInstance().isFloodgatePlayer(uuid)) return true;
        } catch (Throwable ignored) {
            // Floodgate API threw — fall through to UUID/name checks.
        }
        if (uuid.getMostSignificantBits() == 0L) return true;
        String name = player.getName();
        return name != null && BEDROCK_NAME_PATTERN.matcher(name).matches();
    }
}
