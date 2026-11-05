package com.magmaguy.easyminecraftgoals.v1_21_R7_common.hitbox;

import com.magmaguy.easyminecraftgoals.v1_21_R7_common.CraftBukkitBridge;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;

import java.lang.reflect.Field;

public class Hitbox {
    // Cache the field for performance
    private static Field dimensionsField = null;

    private Hitbox() {
    }

    public static boolean setCustomHitbox(Entity entity, float width, float height, boolean fixed) {
        // The mob's own attachment points stay: a rider's seat, a sheared item's drop and the name tag read them.
        EntityDimensions entityDimensions = new EntityDimensions(width, height, height, entity.getDimensions(entity.getPose()).attachments(), fixed);
        try {
            if (dimensionsField == null) {
                dimensionsField = findDimensionsField();
            }
            dimensionsField.set(entity, entityDimensions);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        entity.setBoundingBox(entityDimensions.makeBoundingBox(entity.position()));
        return true;
    }

    private static Field findDimensionsField() throws NoSuchFieldException {
        // Paper uses Mojang mappings, Spigot uses obfuscated
        String fieldName = CraftBukkitBridge.isPaper() ? "dimensions" : "bz";

        Field field = Entity.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
    }

    /** Gives the entity back the box its type, pose and scale give it, dropping a custom one. */
    public static boolean resetHitbox(Entity entity) {
        entity.refreshDimensions();
        return true;
    }
}
