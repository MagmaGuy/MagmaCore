package com.magmaguy.easyminecraftgoals;

import org.bukkit.inventory.ItemStack;

/**
 * Version-specific encoder that turns an item's data components into the JSON the
 * server's own component codecs write. Each NMS module ships one
 * {@code ItemComponentCodecImpl}; callers go through
 * {@link com.magmaguy.magmacore.util.ItemComponents}, which picks the right one.
 */
public interface ItemComponentCodec {

    /**
     * @param item       the Bukkit stack to read
     * @param patchOnly  {@code true} for the components the stack changes against its item's
     *                   prototype, with each removed prototype component written as
     *                   {@code "!namespace:id": {}}; {@code false} for every component the
     *                   stack effectively has
     * @return a JSON object keyed by component id, such as
     *         {@code {"minecraft:max_stack_size":16,"!minecraft:food":{}}}. A component whose
     *         codec cannot encode its value is left out.
     */
    String componentsJson(ItemStack item, boolean patchOnly);
}
