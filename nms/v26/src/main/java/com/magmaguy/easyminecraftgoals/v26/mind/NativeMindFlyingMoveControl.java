package com.magmaguy.easyminecraftgoals.v26.mind;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;

/** Flying control that falls back to movement speed when a carrier has no flying-speed attribute. */
final class NativeMindFlyingMoveControl extends MoveControl<Mob> {
    private static final double ARRIVAL_DISTANCE_SQUARED = 2.5000003E-7D;
    private final int maximumPitchTurn;
    private final boolean hoversInPlace;

    NativeMindFlyingMoveControl(Mob mob, int maximumPitchTurn, boolean hoversInPlace) {
        super(mob);
        this.maximumPitchTurn = maximumPitchTurn;
        this.hoversInPlace = hoversInPlace;
    }

    @Override
    public void tick() {
        if (operation != Operation.MOVE_TO) {
            if (!hoversInPlace) mob.setNoGravity(false);
            mob.setYya(0.0F);
            mob.setZza(0.0F);
            return;
        }

        operation = Operation.WAIT;
        mob.setNoGravity(true);
        double x = wantedX - mob.getX();
        double y = wantedY - mob.getY();
        double z = wantedZ - mob.getZ();
        double distanceSquared = x * x + y * y + z * z;
        if (distanceSquared < ARRIVAL_DISTANCE_SQUARED) {
            mob.setYya(0.0F);
            mob.setZza(0.0F);
            return;
        }

        float yaw = (float) (Mth.atan2(z, x) * Mth.RAD_TO_DEG) - 90.0F;
        mob.setYRot(rotlerp(mob.getYRot(), yaw, 90.0F));
        double speedAttribute = mob.onGround()
                ? mob.getAttributeValue(Attributes.MOVEMENT_SPEED)
                : mob.getAttributes().hasAttribute(Attributes.FLYING_SPEED)
                        ? mob.getAttributeValue(Attributes.FLYING_SPEED)
                        : mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        float speed = (float) (speedModifier * speedAttribute);
        mob.setSpeed(speed);
        double horizontalDistance = Math.sqrt(x * x + z * z);
        if (Math.abs(y) > 1.0E-5D || Math.abs(horizontalDistance) > 1.0E-5D) {
            float pitch = (float) (-(Mth.atan2(y, horizontalDistance) * Mth.RAD_TO_DEG));
            mob.setXRot(rotlerp(mob.getXRot(), pitch, maximumPitchTurn));
            mob.setYya(y > 0.0D ? speed : -speed);
        }
    }
}
