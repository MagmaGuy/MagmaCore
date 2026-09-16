package com.magmaguy.easyminecraftgoals.thirdparty;

import org.bukkit.entity.Player;
import org.geysermc.geyser.api.GeyserApi;

/**
 * Geyser integration for Bedrock player detection.
 * This class is only loaded when Geyser-Spigot is present.
 */
class Geyser {
    private Geyser() {
    }

    static boolean isBedrock(Player player) {
        try {
            GeyserApi api = GeyserApi.api();
            return api != null && api.connectionByUuid(player.getUniqueId()) != null;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }
}
