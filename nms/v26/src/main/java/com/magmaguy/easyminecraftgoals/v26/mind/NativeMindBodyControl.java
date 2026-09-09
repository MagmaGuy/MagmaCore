package com.magmaguy.easyminecraftgoals.v26.mind;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.ai.MindBodyProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;

/** Owns the clean-slate controls installed on one factory-created vanilla carrier. */
final class NativeMindBodyControl {
    private static final Field NAVIGATION = field("navigation");
    private static final Field MOVE_CONTROL = field("moveControl");
    private static final Field GOAL_SELECTOR = field("goalSelector");
    private static final Field TARGET_SELECTOR = field("targetSelector");

    private final Mob mob;
    private final MindBodyProfile profile;
    private Vec3 flightVelocity;

    NativeMindBodyControl(Mob mob, MindBodyProfile profile) {
        this.mob = mob;
        this.profile = profile;
        applyProfile();
    }

    void beforeMindTick() {
        flightVelocity = null;
        // NoAI suppresses carrier-specific serverAiStep implementations such as Bat roosting and
        // zombie sunlight behavior. MagmaCore advances navigation and controls explicitly below.
        mob.setNoAi(true);
        mob.getSensing().tick();
        if (profile.locomotion() == MindBodyLocomotion.AQUATIC
                || profile.locomotion() == MindBodyLocomotion.AMPHIBIOUS) {
            mob.setAirSupply(mob.getMaxAirSupply());
        }
        if (profile.locomotion() == MindBodyLocomotion.FLYING) mob.setNoGravity(true);
        if (profile.locomotion() == MindBodyLocomotion.STATIONARY) stopMovement();
    }

    void afterMindTick() {
        if (flightVelocity != null) {
            mob.getNavigation().stop();
            mob.getMoveControl().setWait();
            mob.setNoGravity(true);
            mob.setDeltaMovement(flightVelocity);
            mob.move(net.minecraft.world.entity.MoverType.SELF, flightVelocity);
            mob.fallDistance = 0;
            double horizontal = flightVelocity.horizontalDistance();
            if (horizontal > 1.0E-5) {
                float desired = (float) (Math.toDegrees(Math.atan2(-flightVelocity.x, flightVelocity.z)));
                float difference = net.minecraft.util.Mth.wrapDegrees(desired - mob.getYRot());
                mob.setYRot(mob.getYRot() + net.minecraft.util.Mth.clamp(difference, -8F, 8F));
                mob.setYBodyRot(mob.getYRot());
                mob.setYHeadRot(mob.getYRot());
            }
            return;
        }
        if (profile.locomotion() == MindBodyLocomotion.STATIONARY) {
            stopMovement();
            return;
        }
        mob.getNavigation().tick();
        mob.getMoveControl().tick();
        mob.getLookControl().tick();
        mob.getJumpControl().tick();
        // LivingEntity.aiStep skips travel when NoAI makes isEffectiveAi false. Advance the
        // native collision/friction/gravity step here too, or a fresh body never even lands
        // and GroundPathNavigation cannot start. Keep carrier AI disabled throughout.
        mob.travel(new Vec3(mob.xxa, mob.yya, mob.zza));
    }

    void stopMovement() {
        flightVelocity = null;
        mob.getNavigation().stop();
        mob.getMoveControl().setWait();
        mob.stopInPlace();
    }

    void steerFlight(Vec3 velocity) { flightVelocity = velocity; }

    private void applyProfile() {
        mob.removeFreeWill();
        // Spigot widens these fields, but Paper retains Minecraft's protected visibility.
        // Use the same cached access path as navigation and movement controls on both runtimes.
        set(GOAL_SELECTOR, new GoalSelector());
        set(TARGET_SELECTOR, new GoalSelector());
        mob.setNoAi(true);
        set(NAVIGATION, navigation());
        set(MOVE_CONTROL, moveControl());
        mob.setNoGravity(profile.locomotion() == MindBodyLocomotion.FLYING);
        applyUniformScale();
        if (profile.locomotion() == MindBodyLocomotion.STATIONARY) stopMovement();
    }

    private PathNavigation navigation() {
        ServerLevel level = (ServerLevel) mob.level();
        return switch (profile.locomotion()) {
            case GROUNDED, STATIONARY -> new GroundPathNavigation(mob, level);
            case FLYING -> new FlyingPathNavigation(mob, level);
            case AQUATIC -> {
                WaterBoundPathNavigation navigation = new WaterBoundPathNavigation(mob, level);
                navigation.setCanFloat(true);
                yield navigation;
            }
            case AMPHIBIOUS -> {
                AmphibiousPathNavigation navigation = new AmphibiousPathNavigation(mob, level);
                navigation.setCanFloat(true);
                yield navigation;
            }
        };
    }

    private MoveControl<?> moveControl() {
        return switch (profile.locomotion()) {
            case GROUNDED, AMPHIBIOUS, STATIONARY -> new MoveControl<>(mob);
            case FLYING -> new NativeMindFlyingMoveControl(mob, 20, true);
            case AQUATIC -> new SmoothSwimmingMoveControl<>(mob, 85, 10, 0.02F, 0.1F, true);
        };
    }

    private void applyUniformScale() {
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale == null) {
            throw new IllegalStateException("Native Mind carrier is missing Minecraft's scale attribute");
        }
        scale.setBaseValue(profile.uniformScale());
    }

    private void set(Field field, Object value) {
        try {
            field.set(mob, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not install native Mind body controls", exception);
        }
    }

    private static Field field(String name) {
        try {
            Field field = Mob.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
