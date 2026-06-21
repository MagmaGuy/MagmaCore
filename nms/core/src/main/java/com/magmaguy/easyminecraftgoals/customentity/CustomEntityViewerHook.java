package com.magmaguy.easyminecraftgoals.customentity;

import org.bukkit.entity.Player;

@FunctionalInterface
public interface CustomEntityViewerHook {
    void handle(CustomEntityHandle entity, Player player);
}
