package com.magmaguy.easyminecraftgoals.v26.packets;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.decoration.ArmorStand;

final class PacketEntityTypes {

    static final EntityType<ArmorStand> ARMOR_STAND = get("armor_stand");
    static final EntityType<Interaction> INTERACTION = get("interaction");
    static final EntityType<Display.ItemDisplay> ITEM_DISPLAY = get("item_display");
    static final EntityType<Display.TextDisplay> TEXT_DISPLAY = get("text_display");

    private PacketEntityTypes() {
    }

    @SuppressWarnings("unchecked")
    private static <T extends Entity> EntityType<T> get(String key) {
        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(key));
        if (entityType == null) {
            throw new IllegalStateException("Missing built-in entity type: minecraft:" + key);
        }
        return (EntityType<T>) entityType;
    }
}
