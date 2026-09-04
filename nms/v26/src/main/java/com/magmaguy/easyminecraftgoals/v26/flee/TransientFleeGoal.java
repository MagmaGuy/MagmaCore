package com.magmaguy.easyminecraftgoals.v26.flee;

import com.magmaguy.easyminecraftgoals.TransientMovementOverride;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.EnumSet;
import java.util.Objects;

/** A removable high-priority goal that owns no permanent Mob state. */
public final class TransientFleeGoal extends Goal implements TransientMovementOverride {
    public static final int PRIORITY = -2;
    private static final int REPATH_INTERVAL_TICKS = 5;

    private final PathfinderMob mob;
    private final double speedModifier;
    private final World world;
    private final TransientGoalRegistration registration;
    private Location threatLocation;
    private Path path;
    private int repathTicks;
    private boolean active = true;

    public TransientFleeGoal(
            PathfinderMob mob,
            World world,
            Location threatLocation,
            double speedModifier) {
        this.mob = Objects.requireNonNull(mob, "mob");
        this.world = Objects.requireNonNull(world, "world");
        this.registration = new TransientGoalRegistration(
                () -> mob.getGoalSelector().addGoal(PRIORITY, this),
                () -> mob.getGoalSelector().removeGoal(this));
        validateSpeed(speedModifier);
        this.speedModifier = speedModifier;
        if (!retarget(threatLocation)) {
            throw new IllegalArgumentException("Threat must be in the fleeing entity's world");
        }
        setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
    }

    public void register() {
        if (!active) return;
        registration.register();
    }

    @Override
    public boolean canUse() {
        if (!canContinue()) return false;
        path = FleePathfinder.findPath(mob, threatLocation);
        return path != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canContinue();
    }

    @Override
    public boolean isInterruptable() {
        return false;
    }

    @Override
    public void start() {
        repathTicks = REPATH_INTERVAL_TICKS;
        moveAlong(path);
    }

    @Override
    public void tick() {
        if (--repathTicks > 0 && !mob.getNavigation().isDone()) return;
        repathTicks = REPATH_INTERVAL_TICKS;
        path = FleePathfinder.findPath(mob, threatLocation);
        moveAlong(path);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        path = null;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public boolean retarget(Location nextThreat) {
        if (!active || nextThreat == null || nextThreat.getWorld() == null) return false;
        if (nextThreat.getWorld() != world) return false;
        threatLocation = nextThreat.clone();
        return true;
    }

    @Override
    public void close() {
        if (!active) return;
        active = false;
        registration.close();
    }

    private boolean canContinue() {
        return active && mob.isAlive() && !mob.isRemoved() && !mob.isNoAi();
    }

    private void moveAlong(Path nextPath) {
        if (nextPath != null) mob.getNavigation().moveTo(nextPath, speedModifier);
    }

    private static void validateSpeed(double speedModifier) {
        if (!Double.isFinite(speedModifier) || speedModifier <= 0D) {
            throw new IllegalArgumentException("speedModifier must be finite and positive");
        }
    }
}
