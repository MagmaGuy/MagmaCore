package com.magmaguy.easyminecraftgoals.v1_21_R2.hitbox;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;

import java.lang.reflect.Field;

public class Hitbox {
    private Hitbox() {
    }

    public static boolean setCustomHitbox(Entity entity, float width, float height, boolean fixed) {
        // The mob's own attachment points stay: a rider's seat, a sheared item's drop and the name tag read them.
        EntityDimensions entityDimensions = new EntityDimensions(width, height, height, entity.getDimensions(entity.getPose()).attachments(), fixed);
        Class<?> entityClass = Entity.class;
        try {
            Field field = entityClass.getDeclaredField("bb");
            field.setAccessible(true);
            field.set(entity, entityDimensions);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
            return false;
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            return false;
        }
        entity.setBoundingBox(entityDimensions.makeBoundingBox(entity.position()));
        return true;
    }

    /** Gives the entity back the box its type, pose and scale give it, dropping a custom one. */
    public static boolean resetHitbox(Entity entity) {
        entity.refreshDimensions();
        return true;
    }
}
