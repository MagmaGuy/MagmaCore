package com.magmaguy.easyminecraftgoals.internal;

import org.bukkit.entity.Player;

/**
 * Receives a decoded interaction with the exact action and hand sent by the client.
 */
@FunctionalInterface
public interface PacketInteractionCallback {

    void accept(
            Player player,
            PacketEntityInterface packetEntity,
            PacketInteractionContext context);
}
