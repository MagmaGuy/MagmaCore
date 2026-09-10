package com.magmaguy.easyminecraftgoals.mindshared.mind;

import com.magmaguy.magmacore.ai.MindActuator;
import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.ai.MindControl;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.Objects;
import java.util.function.BooleanSupplier;

final class NativeMindActuator {
    private final NativeMindBody body;
    private final Mob mob;
    private final NativeMindNavigationTracker navigation;
    private final BooleanSupplier movementOverridden;

    NativeMindActuator(
            NativeMindBody body,
            NativeMindNavigationTracker navigation,
            BooleanSupplier movementOverridden) {
        this.body = body;
        this.mob = body.mob();
        this.navigation = navigation;
        this.movementOverridden = movementOverridden;
    }

    MindActuator guarded(NativeMindBehaviorControl owner, NativeMindControlLeaseBook leases) {
        return new Guarded(owner, leases);
    }

    void stopAll() {
        body.stopMovement();
        setNativeTarget(null);
        mob.setAggressive(false);
        navigation.reset();
    }

    private void setNativeTarget(net.minecraft.world.entity.LivingEntity target) {
        if (mob.getTarget() == target) return;
        // CraftMob's accepted-target setter does not emit an event. Fire the ordinary event
        // explicitly with our actual reason, then honor cancellation and target replacement.
        org.bukkit.event.entity.EntityTargetLivingEntityEvent event =
                new org.bukkit.event.entity.EntityTargetLivingEntityEvent(
                        body.entity(), target == null ? null : (LivingEntity) target.getBukkitEntity(),
                        org.bukkit.event.entity.EntityTargetEvent.TargetReason.CUSTOM);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) ((org.bukkit.entity.Mob) body.entity()).setTarget(event.getTarget());
    }

    private void requireSameWorld(Location location) {
        if (location.getWorld() == null || location.getWorld() != mob.getBukkitEntity().getWorld()) {
            throw new IllegalArgumentException("Mind actuator destination must be in the body's world");
        }
    }

    private final class Guarded implements MindActuator {
        private final NativeMindBehaviorControl owner;
        private final NativeMindControlLeaseBook leases;

        private Guarded(NativeMindBehaviorControl owner, NativeMindControlLeaseBook leases) {
            this.owner = owner;
            this.leases = leases;
        }

        @Override
        public boolean moveTo(Location destination, double speedModifier) {
            require(MindControl.MOVE);
            if (movementOverridden.getAsBoolean()) return false;
            Objects.requireNonNull(destination, "destination");
            if (!Double.isFinite(speedModifier) || speedModifier <= 0.0D) {
                throw new IllegalArgumentException("speedModifier must be finite and positive");
            }
            requireSameWorld(destination);
            boolean started = body.profile().locomotion() != MindBodyLocomotion.STATIONARY
                    && mob.getNavigation().moveTo(
                            destination.getX(),
                            destination.getY(),
                            destination.getZ(),
                            speedModifier);
            navigation.destinationRequested(destination);
            return started;
        }

        @Override
        public void stopMoving() {
            require(MindControl.MOVE);
            if (movementOverridden.getAsBoolean()) return;
            body.stopMovement();
            navigation.reset();
        }

        @Override
        public boolean steerFlight(org.bukkit.util.Vector velocity) {
            require(MindControl.MOVE);
            require(MindControl.LOOK);
            Objects.requireNonNull(velocity, "velocity");
            if (!Double.isFinite(velocity.getX()) || !Double.isFinite(velocity.getY())
                    || !Double.isFinite(velocity.getZ()))
                throw new IllegalArgumentException("Flight velocity must be finite");
            if (movementOverridden.getAsBoolean() || body.profile().locomotion() != MindBodyLocomotion.FLYING)
                return false;
            body.steerFlight(new net.minecraft.world.phys.Vec3(velocity.getX(), velocity.getY(), velocity.getZ()));
            return true;
        }

        @Override
        public void lookAt(Location destination) {
            require(MindControl.LOOK);
            Objects.requireNonNull(destination, "destination");
            requireSameWorld(destination);
            mob.getLookControl().setLookAt(
                    destination.getX(),
                    destination.getY(),
                    destination.getZ());
        }

        @Override
        public void jump() {
            require(MindControl.JUMP);
            if (movementOverridden.getAsBoolean()) return;
            if (body.profile().locomotion() == MindBodyLocomotion.STATIONARY) return;
            mob.getJumpControl().jump();
        }

        @Override
        public void setTarget(LivingEntity target) {
            require(MindControl.TARGET);
            Objects.requireNonNull(target, "target");
            net.minecraft.world.entity.LivingEntity nativeTarget =
                    NativeMindVersion.getNMSLivingEntity(target);
            if (nativeTarget.level() != mob.level()) {
                throw new IllegalArgumentException("Mind target must be in the body's world");
            }
            setNativeTarget(nativeTarget);
        }

        @Override
        public void clearTarget() {
            require(MindControl.TARGET);
            setNativeTarget(null);
        }

        @Override
        public void attack(LivingEntity target) {
            require(MindControl.ATTACK);
            Objects.requireNonNull(target, "target");
            net.minecraft.world.entity.LivingEntity nativeTarget =
                    NativeMindVersion.getNMSLivingEntity(target);
            if (nativeTarget.level() != mob.level()) {
                throw new IllegalArgumentException("Mind target must be in the body's world");
            }
            mob.swing(mob.getUsedItemHand());
            mob.doHurtTarget((ServerLevel) mob.level(), nativeTarget);
        }

        @Override
        public void stopAll() {
            if (owner == null) {
                throw new IllegalStateException("Mind sensors cannot use actuators");
            }
            if (holds(MindControl.MOVE) && !movementOverridden.getAsBoolean()) {
                body.stopMovement();
                navigation.reset();
            }
            if (holds(MindControl.TARGET)) setNativeTarget(null);
            if (holds(MindControl.ATTACK)) mob.setAggressive(false);
        }

        private void require(MindControl control) {
            NativeMindHost.requirePrimaryThread();
            if (!holds(control)) {
                throw new IllegalStateException(
                        "Mind behavior must declare and hold the " + control + " control");
            }
        }

        private boolean holds(MindControl control) {
            return owner != null && leases.holds(owner, control);
        }
    }
}
