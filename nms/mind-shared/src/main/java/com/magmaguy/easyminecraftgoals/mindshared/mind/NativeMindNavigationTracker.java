package com.magmaguy.easyminecraftgoals.mindshared.mind;

import com.magmaguy.magmacore.ai.MindNavigationProgress;
import com.magmaguy.magmacore.ai.MindNavigationStatus;
import com.magmaguy.magmacore.ai.MindPosition;
import net.minecraft.world.entity.Mob;
import org.bukkit.Location;

import java.util.Optional;

/** Tick-local native movement diagnostics. No scheduler or asynchronous sampling is involved. */
final class NativeMindNavigationTracker {
    private static final double ARRIVAL_DISTANCE_SQUARED = 0.25D;
    private static final double PROGRESS_DISTANCE_SQUARED = 0.0025D;

    private final Mob mob;
    private MindPosition destination;
    private MindNavigationProgress lastProgress;
    private int consecutiveStuckTicks;

    NativeMindNavigationTracker(Mob mob) {
        this.mob = mob;
    }

    void destinationRequested(Location requested) {
        destination = MindPosition.from(requested);
        if (lastProgress == null) {
            lastProgress = new MindNavigationProgress(
                    mob.level().getGameTime(),
                    currentPosition());
            consecutiveStuckTicks = 0;
        }
    }

    void beginTick(long gameTick) {
        if (destination == null) return;
        MindPosition current = currentPosition();
        if (distanceSquared(current, destination) <= ARRIVAL_DISTANCE_SQUARED) {
            reset();
            return;
        }
        if (distanceSquared(current, lastProgress.position()) >= PROGRESS_DISTANCE_SQUARED) {
            lastProgress = new MindNavigationProgress(gameTick, current);
            consecutiveStuckTicks = 0;
        } else if (consecutiveStuckTicks < Integer.MAX_VALUE) {
            consecutiveStuckTicks++;
        }
    }

    MindNavigationStatus status() {
        if (destination == null) return MindNavigationStatus.idle();
        return new MindNavigationStatus(
                Optional.of(destination),
                !mob.getNavigation().isDone(),
                Optional.of(lastProgress),
                consecutiveStuckTicks);
    }

    void reset() {
        destination = null;
        lastProgress = null;
        consecutiveStuckTicks = 0;
    }

    private MindPosition currentPosition() {
        return MindPosition.from(mob.getBukkitEntity().getLocation());
    }

    private static double distanceSquared(MindPosition first, MindPosition second) {
        if (!first.world().equals(second.world())) return Double.POSITIVE_INFINITY;
        double x = first.x() - second.x();
        double y = first.y() - second.y();
        double z = first.z() - second.z();
        return x * x + y * y + z * z;
    }
}
