package com.magmaguy.easyminecraftgoals.v26.mind;

import com.magmaguy.easyminecraftgoals.TransientMovementOverride;
import com.magmaguy.easyminecraftgoals.v26.flee.FleePathfinder;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.pathfinder.Path;
import org.bukkit.Location;

import java.util.Objects;

/** Session-owned locomotion override for a clean-slate native Mind body. */
final class NativeMindFleeOverride implements TransientMovementOverride {
    private static final int REPATH_INTERVAL_TICKS = 5;

    private final NativeMindSession session;
    private final PathfinderMob mob;
    private final double speedModifier;
    private final NativeMindMovementOverrideGate.Lease gateLease;
    private Location threatLocation;
    private Path path;
    private int repathTicks;
    private boolean active = true;

    NativeMindFleeOverride(
            NativeMindSession session,
            PathfinderMob mob,
            Location threatLocation,
            double speedModifier,
            Path initialPath,
            NativeMindMovementOverrideGate.Lease gateLease) {
        this.session = Objects.requireNonNull(session, "session");
        this.mob = Objects.requireNonNull(mob, "mob");
        if (!Double.isFinite(speedModifier) || speedModifier <= 0D) {
            throw new IllegalArgumentException("speedModifier must be finite and positive");
        }
        this.speedModifier = speedModifier;
        this.path = Objects.requireNonNull(initialPath, "initialPath");
        this.repathTicks = REPATH_INTERVAL_TICKS;
        this.gateLease = Objects.requireNonNull(gateLease, "gateLease");
        if (!retarget(threatLocation)) {
            gateLease.close();
            throw new IllegalArgumentException("Threat must be in the fleeing entity's world");
        }
    }

    void tick() {
        if (!isActive() || !session.hasBody(mob.getUUID())) {
            close();
            return;
        }
        if (--repathTicks <= 0 || path == null || mob.getNavigation().isDone()) {
            repathTicks = REPATH_INTERVAL_TICKS;
            path = FleePathfinder.findPath(mob, threatLocation);
        }
        if (path != null) mob.getNavigation().moveTo(path, speedModifier);
    }

    @Override
    public boolean isActive() {
        return active && mob.isAlive() && !mob.isRemoved();
    }

    @Override
    public boolean retarget(Location nextThreat) {
        NativeMindHost.requirePrimaryThread();
        if (!active || nextThreat == null || nextThreat.getWorld() == null) return false;
        if (nextThreat.getWorld() != mob.getBukkitEntity().getWorld()) return false;
        threatLocation = nextThreat.clone();
        return true;
    }

    @Override
    public void close() {
        NativeMindHost.requirePrimaryThread();
        if (!active) return;
        active = false;
        session.removeMovementOverride(this);
        gateLease.close();
        path = null;
    }
}
