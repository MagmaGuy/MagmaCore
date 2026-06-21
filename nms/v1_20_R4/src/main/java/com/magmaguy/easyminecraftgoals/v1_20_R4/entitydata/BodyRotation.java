package com.magmaguy.easyminecraftgoals.v1_20_R4.entitydata;

import net.minecraft.world.entity.Entity;

public class BodyRotation {
    public static float getBodyRotation(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.LivingEntity livingEntity) {
            return livingEntity.yBodyRot;
        }
        return entity.getYRot();
    }
}
