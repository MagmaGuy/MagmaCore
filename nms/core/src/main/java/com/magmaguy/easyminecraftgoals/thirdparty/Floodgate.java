package com.magmaguy.easyminecraftgoals.thirdparty;

import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

/** Optional API access, isolated so Floodgate is not needed to load BedrockChecker. */
class Floodgate {
    private Floodgate() {
    }

    static boolean isBedrock(Player player) {
        try {
            FloodgateApi api = FloodgateApi.getInstance();
            return api != null && api.isFloodgatePlayer(player.getUniqueId());
        } catch (RuntimeException | LinkageError ignored) {
            // An unavailable optional API must not prevent checking the other provider.
            return false;
        }
    }
}
