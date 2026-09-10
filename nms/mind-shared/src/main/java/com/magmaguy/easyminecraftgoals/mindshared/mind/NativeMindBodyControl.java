package com.magmaguy.easyminecraftgoals.mindshared.mind;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.ai.MindBodyProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.SmoothSwimmingLookControl;
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
    private static final Field NAVIGATION = field(PathNavigation.class);
    private static final Field MOVE_CONTROL = field(MoveControl.class);
    private static final Field LOOK_CONTROL = field(LookControl.class);
    private static final Field ATTRIBUTE_INSTANCES = attributeInstances();

    private final Mob mob;
    private final MindBodyProfile profile;
    private final Field squidMovementVector;
    private Vec3 flightVelocity;

    NativeMindBodyControl(Mob mob, MindBodyProfile profile) {
        this.mob = mob;
        this.profile = profile;
        squidMovementVector = squidMovementVector(mob, profile);
        applyProfile();
    }

    void beforeMindTick() {
        flightVelocity = null;
        // NoAI suppresses native decision loops and zombie underwater conversion.
        // Sunlight ignition is handled separately by the host's combustion listener.
        mob.setNoAi(true);
        mob.getSensing().tick();
        if (profile.locomotion() == MindBodyLocomotion.AQUATIC
                || profile.locomotion() == MindBodyLocomotion.AMPHIBIOUS) {
            mob.setAirSupply(mob.getMaxAirSupply());
        }
        if (profile.locomotion() == MindBodyLocomotion.FLYING) mob.setNoGravity(true);
        if (squidMovementVector != null && !mob.isInWater()) setSquidMovement(Vec3.ZERO);
        if (profile.locomotion() == MindBodyLocomotion.STATIONARY) stopMovement();
    }

    void afterMindTick() {
        if (flightVelocity != null) {
            mob.getNavigation().stop();
            NativeMindVersion.stopControl(mob.getMoveControl());
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
        if (squidMovementVector != null && mob.isInWater()) {
            // Swimming control emits local steering scaled by the movement attribute and
            // navigation speed. Squid.travel ignores that input and consumes delta instead.
            // Preserve the native squid's 0.2 swim speed at its default movement attribute.
            Vec3 movement = new Vec3(mob.xxa, mob.yya, mob.zza)
                    .yRot((float) Math.toRadians(-mob.getYRot()))
                    .scale(0.2D / Attributes.MOVEMENT_SPEED.value().getDefaultValue());
            setSquidMovement(movement);
        }
        // LivingEntity.aiStep skips travel when NoAI makes isEffectiveAi false. Advance the
        // native collision/friction/gravity step here too, or a fresh body never even lands
        // and GroundPathNavigation cannot start. The version bridge handles older travel guards
        // without running an entity tick, goal selector, or native Brain.
        NativeMindVersion.advancePhysics(mob, new Vec3(mob.xxa, mob.yya, mob.zza));
    }

    void stopMovement() {
        flightVelocity = null;
        mob.getNavigation().stop();
        NativeMindVersion.stopControl(mob.getMoveControl());
        mob.stopInPlace();
        if (squidMovementVector != null) setSquidMovement(Vec3.ZERO);
    }

    void steerFlight(Vec3 velocity) { flightVelocity = velocity; }

    void tickPausedPhysics() {
        beforeMindTick();
        mob.getNavigation().stop();
        NativeMindVersion.stopControl(mob.getMoveControl());
        mob.setXxa(0);
        mob.setYya(0);
        mob.setZza(0);
        if (squidMovementVector != null) setSquidMovement(Vec3.ZERO);
        if (profile.locomotion() != MindBodyLocomotion.STATIONARY) NativeMindVersion.advancePhysics(mob, Vec3.ZERO);
    }

    private void applyProfile() {
        mob.removeFreeWill();
        // removeFreeWill clears the movement selector, but older versions retain
        // targetSelector entries. Clear both without depending on obfuscated names.
        for (Field field : Mob.class.getDeclaredFields()) {
            if (field.getType() != GoalSelector.class) continue;
            try {
                field.setAccessible(true);
                ((GoalSelector) field.get(mob)).removeAllGoals(goal -> true);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Could not clear native target goals", failure);
            }
        }
        mob.setNoAi(true);
        // Bat.tick pins resting bats in place even when their native AI is disabled.
        if (mob.getBukkitEntity() instanceof org.bukkit.entity.Bat bat) bat.setAwake(true);
        set(NAVIGATION, navigation());
        set(MOVE_CONTROL, moveControl());
        // The ordinary look controller resets body pitch each tick, preventing the swimming
        // move controller from steering downward against buoyancy while pursuing a target.
        if (profile.locomotion() == MindBodyLocomotion.AQUATIC)
            set(LOOK_CONTROL, new SmoothSwimmingLookControl(mob, 10));
        mob.setNoGravity(profile.locomotion() == MindBodyLocomotion.FLYING);
        applyUniformScale();
        ensureAttribute(Attributes.ATTACK_DAMAGE, 2D);
        ensureAttribute(Attributes.ATTACK_KNOCKBACK, 0D);
        if (profile.locomotion() == MindBodyLocomotion.STATIONARY) stopMovement();
    }

    private void setSquidMovement(Vec3 movement) {
        set(squidMovementVector, movement);
        // Squid.aiStep restores this vector during its swim animation, even with NoAI.
        // Update both values so entity-tick ordering cannot restore stale steering. On
        // land only clear the steering, leaving native gravity/levitation velocity intact.
        if (mob.isInWater()) mob.setDeltaMovement(movement);
    }

    private static Field squidMovementVector(Mob mob, MindBodyProfile profile) {
        if (profile.locomotion() != MindBodyLocomotion.AQUATIC
                || !(mob.getBukkitEntity() instanceof org.bukkit.entity.Squid)) return null;
        // Squid's package and this field's visibility vary across adapters. Its one native
        // Vec3 field is the swim vector; resolve once per body, including GlowSquid's parent.
        Field movement = null;
        for (Class<?> type = mob.getClass(); type != Mob.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != Vec3.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (movement != null) throw new IllegalStateException("Native squid swim vector is ambiguous");
                movement = field;
            }
        }
        if (movement == null) throw new IllegalStateException("Native squid swim vector is unavailable");
        movement.setAccessible(true);
        return movement;
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

    private MoveControl moveControl() {
        return switch (profile.locomotion()) {
            case GROUNDED, AMPHIBIOUS, STATIONARY -> new MoveControl(mob);
            case FLYING -> new NativeMindFlyingMoveControl(mob, 20, true);
            // Native travel owns fluid forces. The controller's optional upward impulse
            // nearly cancels a small carrier's dive thrust when it has a combat target.
            case AQUATIC -> new SmoothSwimmingMoveControl(mob, 85, 10, 0.02F, 0.1F, false);
        };
    }

    private void applyUniformScale() {
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale == null) {
            throw new IllegalStateException("Native Mind carrier is missing Minecraft's scale attribute");
        }
        scale.setBaseValue(profile.uniformScale());
    }

    @SuppressWarnings("unchecked")
    private void ensureAttribute(Holder<Attribute> attribute, double baseline) {
        AttributeMap attributes = mob.getAttributes();
        if (attributes.hasAttribute(attribute)) return;
        AttributeInstance instance = new AttributeInstance(attribute, changed -> {
            if (attribute.value().isClientSyncable()) attributes.getAttributesToSync().add(changed);
            attributes.getAttributesToUpdate().add(changed);
        });
        try {
            ((java.util.Map<Holder<Attribute>, AttributeInstance>) ATTRIBUTE_INSTANCES.get(attributes))
                    .put(attribute, instance);
            instance.setBaseValue(baseline);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Could not install native melee attributes", failure);
        }
    }

    private static Field attributeInstances() {
        for (Field field : AttributeMap.class.getDeclaredFields()) {
            if (field.getType() != java.util.Map.class) continue;
            field.setAccessible(true);
            return field;
        }
        throw new ExceptionInInitializerError("Native attribute instance map is unavailable");
    }

    private void set(Field field, Object value) {
        try {
            field.set(mob, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not install native Mind body controls", exception);
        }
    }

    private static Field field(Class<?> type) {
        try {
            for (Field field : Mob.class.getDeclaredFields()) {
                if (field.getType() != type) continue;
                field.setAccessible(true);
                return field;
            }
            throw new NoSuchFieldException(type.getName());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
