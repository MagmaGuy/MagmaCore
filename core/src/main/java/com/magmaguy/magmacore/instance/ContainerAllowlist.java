package com.magmaguy.magmacore.instance;

import org.bukkit.block.Block;

/**
 * Optional provider hook: lets a host plugin allow specific containers
 * (chests, barrels, shulker boxes) to be opened inside a protected world.
 *
 * EliteMobs registers one that returns true for blocks tagged as treasure
 * chests. ETD and other plugins can leave this unset, in which case every
 * container in a protected world is blocked.
 */
@FunctionalInterface
public interface ContainerAllowlist {
    boolean isAllowed(Block block);
}
