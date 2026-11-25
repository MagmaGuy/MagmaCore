package com.magmaguy.easyminecraftgoals.v1_21_R7_common.entitydata;

import net.minecraft.world.entity.Entity;

public class BodyRotation {
    public static float getBodyRotation(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.LivingEntity livingEntity) {
            return livingEntity.yBodyRot;
        }
        return entity.getYRot();
    }

    /** The yaw and pitch, in degrees, of a wither's side head: 0 is its right head, 1 its left. */
    public static float[] getWitherHeadRotation(Entity entity, int head) {
        if (!(entity instanceof net.minecraft.world.entity.boss.wither.WitherBoss wither) || head < 0 || head > 1)
            return null;
        return new float[]{wither.getHeadYRots()[head], wither.getHeadXRots()[head]};
    }
}
