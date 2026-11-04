package com.magmaguy.easyminecraftgoals.mindshared.mind;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.BodyRotationControl;

/**
 * Vanilla's body rotation, told when the Mind moved the body. Vanilla turns a walking mob's body to its heading only
 * when the entity tick sees it move, comparing its position with where it stood as that tick began. The Mind moves
 * its body from the scheduler, before the tick records that starting position, so vanilla never saw the move: the
 * body kept its old facing while the mob walked, and the head, which turns toward the body, kept it too. On a tick
 * the Mind moved the body this does what vanilla does for a moving mob; on any other tick it is vanilla's own.
 */
final class NativeMindBodyRotationControl extends BodyRotationControl {
    private final Mob mob;
    private boolean moved;

    NativeMindBodyRotationControl(Mob mob) {
        super(mob);
        this.mob = mob;
    }

    /** The Mind moved the body this tick: its body faces its heading now and through the coming entity tick. */
    void moved() {
        moved = true;
        faceHeading(mob);
    }

    @Override
    public void clientTick() {
        if (moved) {
            moved = false;
            faceHeading(mob);
            return;
        }
        super.clientTick();
    }

    /** As vanilla turns a moving mob: its body faces its heading and its head stays within its turning range of it. */
    static void faceHeading(Mob mob) {
        float heading = mob.getYRot();
        mob.setYBodyRot(heading);
        mob.setYHeadRot(Mth.rotateIfNecessary(mob.getYHeadRot(), heading, mob.getMaxHeadYRot()));
    }
}
