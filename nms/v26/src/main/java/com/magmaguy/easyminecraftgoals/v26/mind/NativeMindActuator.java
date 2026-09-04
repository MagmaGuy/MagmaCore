package com.magmaguy.easyminecraftgoals.v26.mind;

import com.magmaguy.easyminecraftgoals.v26.CraftBukkitBridge;
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
        mob.getNavigation().stop();
        mob.getMoveControl().setWait();
        mob.stopInPlace();
        mob.setTarget(null);
        mob.setAggressive(false);
        navigation.reset();
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
            mob.getNavigation().stop();
            mob.getMoveControl().setWait();
            mob.stopInPlace();
            navigation.reset();
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
                    CraftBukkitBridge.getNMSLivingEntity(target);
            if (nativeTarget.level() != mob.level()) {
                throw new IllegalArgumentException("Mind target must be in the body's world");
            }
            mob.setTarget(nativeTarget);
        }

        @Override
        public void clearTarget() {
            require(MindControl.TARGET);
            mob.setTarget(null);
        }

        @Override
        public void attack(LivingEntity target) {
            require(MindControl.ATTACK);
            Objects.requireNonNull(target, "target");
            net.minecraft.world.entity.LivingEntity nativeTarget =
                    CraftBukkitBridge.getNMSLivingEntity(target);
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
                mob.getNavigation().stop();
                mob.getMoveControl().setWait();
                mob.stopInPlace();
                navigation.reset();
            }
            if (holds(MindControl.TARGET)) mob.setTarget(null);
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
