package com.magmaguy.easyminecraftgoals.v26.hitbox;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;

import java.lang.reflect.Field;

public class Hitbox {
    // Cache the field for performance — the lookup result is recorded once,
    // success or failure, so a single mapping mismatch can't flood the log
    // with 26 identical stack traces per spawn batch.
    private static Field dimensionsField = null;
    private static boolean dimensionsFieldLookupTried = false;

    private Hitbox() {
    }

    public static boolean setCustomHitbox(Entity entity, float width, float height, boolean fixed) {
        if (!dimensionsFieldLookupTried) {
            dimensionsFieldLookupTried = true;
            dimensionsField = findDimensionsField();
        }
        if (dimensionsField == null) return false;

        EntityDimensions entityDimensions = new EntityDimensions(width, height, height, null, fixed);
        try {
            dimensionsField.set(entity, entityDimensions);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        entity.setBoundingBox(entityDimensions.makeBoundingBox(entity.position()));
        return true;
    }

    /**
     * MC 26.1+ ships fully unobfuscated NMS on both Paper and Spigot, so we
     * can find the {@code EntityDimensions} field by type instead of name —
     * future-proof against field renames and identical across forks. Returns
     * {@code null} on failure; the caller caches that outcome so we only log
     * the failure once.
     */
    private static Field findDimensionsField() {
        for (Field f : Entity.class.getDeclaredFields()) {
            if (f.getType() == EntityDimensions.class) {
                f.setAccessible(true);
                return f;
            }
        }
        new RuntimeException("Hitbox: no EntityDimensions field on Entity.class; custom hitboxes disabled this session").printStackTrace();
        return null;
    }
}
