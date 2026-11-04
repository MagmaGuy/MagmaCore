package com.magmaguy.easyminecraftgoals.mindshared.mind;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.ai.MindBodyProfile;
import com.magmaguy.magmacore.ai.MindSwimWave;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
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
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;

/** Owns the clean-slate controls installed on one factory-created vanilla carrier. */
final class NativeMindBodyControl {
    private static final Field NAVIGATION = field(PathNavigation.class);
    private static final Field MOVE_CONTROL = field(MoveControl.class);
    private static final Field LOOK_CONTROL = field(LookControl.class);
    private static final Field BODY_ROTATION_CONTROL = field(BodyRotationControl.class);
    // Vanilla's BodyRotationControl counts a mob as moving past this squared horizontal step.
    private static final double MOVING_SQUARED = 2.5000003E-7D;
    // A swimmer's speed against the walk its movement speed and speed modifier make on grass: half again as fast.
    private static final double SWIM_SPEEDUP = 1.5D;
    // A mob walking on grass reaches 0.98 x speed squared x WALK_TERMINAL blocks per tick: vanilla's ground travel
    // adds 0.98 x speed x speed each tick and keeps 0.546 of its velocity, so it settles at 0.546 / (1 - 0.546) of it.
    private static final double WALK_TERMINAL = 0.546D / (1D - 0.546D);
    // Share of the gap to the wanted swimming velocity closed each tick, so a swimmer glides rather than snaps.
    private static final double SWIM_ACCELERATION = 0.25D;
    // A swimmer this close to where its path wants it has arrived.
    private static final double SWIM_ARRIVAL = 0.1D;
    // Degrees a swimmer's heading turns per tick at most.
    private static final float SWIM_TURN = 20F;
    // Below this share of its height under water a swimmer is at the surface: it swims on rather than climbs out,
    // unless a shore stops it.
    private static final double SURFACE_SHARE = 0.85D;
    // Blocks per tick a swimmer rises climbing out onto a shore: the speed of vanilla's hop out of water.
    private static final double CLIMB_SPEED = 0.3D;
    // How far onto a shore a swimmer steps once level with its top; it needs room to stand that far in.
    private static final double SHORE_STEP = 0.2D;
    // The height steps it tries, finding a shore's top.
    private static final double SHORE_PROBE = 1D / 16D;
    private static final Field ATTRIBUTE_INSTANCES = attributeInstances();

    private final Mob mob;
    private final MindBodyProfile profile;
    private final Field squidMovementVector;
    private final NativeMindBodyRotationControl bodyRotation;
    private Vec3 flightVelocity;
    // Radians along the swim wave; it advances while the body swims.
    private double wavePhase;
    // A swimmer climbing out onto a shore: the height its feet rise to, NaN when it isn't climbing, the way it steps
    // onto the shore, and the ticks the climb has left.
    private double climbY = Double.NaN;
    private double climbX, climbZ;
    private int climbTicks;

    NativeMindBodyControl(Mob mob, MindBodyProfile profile) {
        this.mob = mob;
        this.profile = profile;
        squidMovementVector = squidMovementVector(mob, profile);
        bodyRotation = new NativeMindBodyRotationControl(mob);
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
        double startX = mob.getX(), startZ = mob.getZ();
        mob.getNavigation().tick();
        if (!Double.isNaN(climbY)) {
            climbOut();
            faceIfMoved(startX, startZ);
            return;
        }
        if (swimming()) {
            swim();
            faceIfMoved(startX, startZ);
            return;
        }
        mob.getMoveControl().tick();
        mob.getLookControl().tick();
        mob.getJumpControl().tick();
        if (squidMovementVector != null && mob.isInWater()) {
            // MoveControl emits 0..360 yaw, but Squid.aiStep blends body yaw toward
            // atan2's -180..180 result without wrapping. Keep equivalent angles in
            // the same range or native animation fights turns across 180 degrees.
            float yaw = net.minecraft.util.Mth.wrapDegrees(mob.getYRot());
            mob.setYRot(yaw);
            mob.setYBodyRot(yaw);
            // Swimming control emits local steering scaled by the movement attribute and
            // navigation speed. Squid.travel ignores that input and consumes delta instead.
            // Preserve the native squid's 0.2 swim speed at its default movement attribute.
            Vec3 movement = new Vec3(mob.xxa, mob.yya, mob.zza)
                    .yRot((float) Math.toRadians(-yaw))
                    .scale(0.2D / Attributes.MOVEMENT_SPEED.value().getDefaultValue());
            setSquidMovement(movement);
        }
        // LivingEntity.aiStep skips travel when NoAI makes isEffectiveAi false. Advance the
        // native collision/friction/gravity step here too, or a fresh body never even lands
        // and GroundPathNavigation cannot start. The version bridge handles older travel guards
        // without running an entity tick, goal selector, or native Brain.
        NativeMindVersion.advancePhysics(mob, new Vec3(mob.xxa, mob.yya, mob.zza));
        // A squid's own aiStep turns its body from its swim vector, set above.
        if (squidMovementVector == null) faceIfMoved(startX, startZ);
    }

    /**
     * Turns the body to its heading when the Mind moved it this tick, as vanilla turns a moving mob. Vanilla's own
     * check runs in the entity tick and compares against a position taken after the Mind already moved the body, so
     * it never sees the move (see {@link NativeMindBodyRotationControl}).
     */
    private void faceIfMoved(double startX, double startZ) {
        double dx = mob.getX() - startX, dz = mob.getZ() - startZ;
        if (dx * dx + dz * dz > MOVING_SQUARED) bodyRotation.moved();
    }

    /**
     * Whether an amphibious body swims this tick rather than walks: it is in water, and not standing on the bottom of
     * water too shallow to swim in.
     */
    private boolean swimming() {
        if (profile.locomotion() != MindBodyLocomotion.AMPHIBIOUS || !mob.isInWater()) return false;
        return !mob.onGround() || mob.getFluidHeight(FluidTags.WATER) > mob.getFluidJumpThreshold();
    }

    /**
     * Swims an amphibious body toward where its path wants it, through the water in three dimensions, as vanilla's
     * swimming animals do. A land mob's own water travel paddles at a fraction of its walking push and sinks, so it
     * walked the bottom instead. The body glides toward its wanted velocity, swims its profile's wave on top of the
     * path's height while it travels, and at the surface swims on rather than climbing out, unless a shore blocks it.
     * Bumping into the shore its path leads onto, it climbs out (see {@link #findShore}). Resting in water it holds
     * its depth. It turns toward its travel a limited amount per tick.
     */
    private void swim() {
        MoveControl move = mob.getMoveControl();
        Vec3 desired = Vec3.ZERO;
        double fullSpeed = 0D;
        double nextY = Double.NaN;
        if (move.hasWanted()) {
            nextY = move.getWantedY();
            // It climbs or dives toward the height its path ends at, as a swimmer heads for the surface, while it
            // steers by the next waypoint. A path through open water keeps its depth from node to node, so aiming at
            // the next one alone left it creeping up, well below where it was going.
            Path path = mob.getNavigation().getPath();
            double wantedY = path != null && !path.isDone() && path.getEndNode() != null ? path.getEndNode().y : move.getWantedY();
            double dx = move.getWantedX() - mob.getX(), dy = wantedY - mob.getY(), dz = move.getWantedZ() - mob.getZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double speed = move.getSpeedModifier() * mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
            fullSpeed = SWIM_SPEEDUP * 0.98D * speed * speed * WALK_TERMINAL;
            if (distance > SWIM_ARRIVAL) desired = new Vec3(dx, dy, dz).scale(Math.min(fullSpeed, distance) / distance);
            NativeMindVersion.stopControl(move);
        }
        MindSwimWave wave = profile.swimWave();
        double horizontal = desired.horizontalDistance();
        if (wave.waves() && horizontal > 1.0E-3D && fullSpeed > 0D) {
            // The height (h / 2) sin(phase) rises at (h / 2) w cos(phase), w = 2 pi / period. Scaled by the share of
            // full speed it swims, the wave fades out as it slows down.
            double omega = Math.PI * 2D / wave.periodTicks();
            wavePhase = (wavePhase + omega) % (Math.PI * 2D);
            double share = Math.min(1D, horizontal / fullSpeed);
            desired = desired.add(0D, wave.height() * 0.5D * omega * Math.cos(wavePhase) * share, 0D);
        }
        if (desired.y > 0D && !mob.horizontalCollision
                && mob.getFluidHeight(FluidTags.WATER) < mob.getBbHeight() * SURFACE_SHARE) {
            desired = new Vec3(desired.x, 0D, desired.z);
        }
        Vec3 velocity = mob.getDeltaMovement();
        Vec3 next = velocity.add(desired.subtract(velocity).scale(SWIM_ACCELERATION));
        mob.setDeltaMovement(next);
        mob.move(MoverType.SELF, next);
        mob.fallDistance = 0;
        double travel = next.horizontalDistance();
        if (travel > 1.0E-3D) {
            float heading = (float) Math.toDegrees(Math.atan2(-next.x, next.z));
            mob.setYRot(mob.getYRot() + Mth.clamp(Mth.wrapDegrees(heading - mob.getYRot()), -SWIM_TURN, SWIM_TURN));
            mob.setXRot((float) -Math.toDegrees(Math.atan2(next.y, travel)));
        }
        if (mob.horizontalCollision && horizontal > 1.0E-3D) findShore(desired.x / horizontal, desired.z / horizontal, nextY);
        mob.getLookControl().tick();
    }

    /**
     * Starts a swimmer at the surface climbing out onto the shore it bumped into, when its path leads out of the water
     * there and the shore's top stands no higher above the water than the swimmer could step up on land. Vanilla
     * lifts anything leaving water with a hop of the same reach (0.6 blocks, a mob's own step height), and this swim
     * replaces vanilla's water travel, hop included. Floating with most of its body under water, the swimmer could
     * only rise until its feet left the water, still short of a shore level with the water, then fell back in. A
     * bank a full block above the water stops it, as it stops a player.
     *
     * @param dirX  the way it swims, horizontally, as is dirZ: a unit vector
     * @param nextY the height of its path's next waypoint, NaN without one
     */
    private void findShore(double dirX, double dirZ, double nextY) {
        double depth = mob.getFluidHeight(FluidTags.WATER);
        // Fully under water it swims up first.
        if (depth <= 0D || depth > mob.getBbHeight()) return;
        // Only where its path climbs out of the water, not when it brushes a bank swimming along it.
        if (!(nextY > mob.getY() + depth)) return;
        AABB box = mob.getBoundingBox();
        double stepX = dirX * SHORE_STEP, stepZ = dirZ * SHORE_STEP;
        double reach = depth + mob.maxUpStep();
        for (double rise = SHORE_PROBE; rise <= reach; rise += SHORE_PROBE) {
            if (!mob.level().noCollision(mob, box.move(stepX, rise, stepZ))) continue;
            climbY = mob.getY() + rise;
            climbX = dirX;
            climbZ = dirZ;
            climbTicks = (int) Math.ceil(rise / CLIMB_SPEED);
            return;
        }
    }

    /**
     * Rises toward the shore's top at the speed of vanilla's hop out of water, then steps onto it and walks from
     * there. Something above it, the shore filling up, or a climb that outlasts its height gives up, and it swims on.
     */
    private void climbOut() {
        double left = climbY - mob.getY();
        AABB box = mob.getBoundingBox();
        if (climbTicks-- < 0 || !mob.level().noCollision(mob, box.move(climbX * SHORE_STEP, left, climbZ * SHORE_STEP))) {
            climbY = Double.NaN;
            return;
        }
        mob.fallDistance = 0;
        if (left > 1.0E-4D) {
            double rise = Math.min(CLIMB_SPEED, left), before = mob.getY();
            Vec3 lift = new Vec3(0D, rise, 0D);
            mob.setDeltaMovement(lift);
            mob.move(MoverType.SELF, lift);
            if (mob.getY() - before < rise * 0.5D) {
                climbY = Double.NaN;
                return;
            }
            if (climbY - mob.getY() > 1.0E-4D) return;
        }
        Vec3 step = new Vec3(climbX * SHORE_STEP, 0D, climbZ * SHORE_STEP);
        mob.move(MoverType.SELF, step);
        // It walks on from rest, rather than sliding on with the step.
        mob.setDeltaMovement(Vec3.ZERO);
        climbY = Double.NaN;
    }

    void stopMovement() {
        flightVelocity = null;
        climbY = Double.NaN;
        mob.getNavigation().stop();
        NativeMindVersion.stopControl(mob.getMoveControl());
        mob.stopInPlace();
        if (squidMovementVector != null) setSquidMovement(Vec3.ZERO);
    }

    void steerFlight(Vec3 velocity) { flightVelocity = velocity; }

    /**
     * Hands the body to vanilla, for a rider whose client steers it. Clearing NoAI lets native
     * ridden travel run; the goal selectors stay empty and the next Mind tick sets NoAI again.
     */
    void release() {
        stopMovement();
        mob.setNoAi(false);
    }

    void tickPausedPhysics() {
        climbY = Double.NaN;
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
        set(BODY_ROTATION_CONTROL, bodyRotation);
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
