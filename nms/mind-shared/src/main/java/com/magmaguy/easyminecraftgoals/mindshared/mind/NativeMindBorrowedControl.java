package com.magmaguy.easyminecraftgoals.mindshared.mind;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.ai.MindBodyProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * The hold on a mob the host borrowed: one Minecraft or another plugin made, which keeps its own navigation, move
 * control, speed and attacks while a Mind drives it, and gets its own AI back when the Mind lets go.
 *
 * <p>While it is held the mob has its AI switched off, which stops its goal selectors and its Brain at once, as it
 * does for a carrier; its running goals and behaviors are stopped first, as vanilla stops them, so none is left
 * half done. Each tick this then runs what the mob's own AI step would have run after its goals: its navigation,
 * its move, look and jump controls, and its travel. Nothing is removed from the mob, so letting go is switching its
 * AI back on. Unlike a carrier it keeps its goals, attributes, scale, persistence and whether sunlight burns it.</p>
 *
 * <p>Minecraft saves the AI switch with the mob; {@link NativeMindBorrowedMarker} covers the copies that outlive a
 * borrow.</p>
 */
final class NativeMindBorrowedControl implements NativeMindBodyDriver {
    private static final List<Field> GOAL_SELECTORS = goalSelectors();

    private final Mob mob;
    private final Plugin plugin;
    private final String hostIdentity;
    private final MindBodyProfile profile;
    // Vanilla's body rotation never sees a move the Mind makes before the entity ticks (see
    // NativeMindBodyRotationControl), so the mob wears this one while it is held.
    private final NativeMindBodyRotationControl bodyRotation;
    private BodyRotationControl ownBodyRotation;
    private boolean held;
    private boolean hadNoAi;
    private boolean hadNoGravity;
    private Vec3 flightVelocity;
    // A creeper swells on the ticks its Mind attacks, and deflates on the others.
    private boolean swelling;

    NativeMindBorrowedControl(Mob mob, Plugin plugin, String hostIdentity) {
        this.mob = mob;
        this.plugin = plugin;
        this.hostIdentity = hostIdentity;
        bodyRotation = new NativeMindBodyRotationControl(mob);
        org.bukkit.entity.LivingEntity entity = (org.bukkit.entity.LivingEntity) mob.getBukkitEntity();
        profile = new MindBodyProfile(
                locomotion(mob.getNavigation()),
                mob.getAttributeValue(Attributes.SCALE),
                entity.isCollidable(),
                entity.getType().getKey().toString());
    }

    MindBodyProfile profile() {
        return profile;
    }

    /** The way the mob's own navigation moves it, which is the way its Mind will. */
    private static MindBodyLocomotion locomotion(PathNavigation navigation) {
        if (navigation instanceof FlyingPathNavigation) return MindBodyLocomotion.FLYING;
        if (navigation instanceof AmphibiousPathNavigation) return MindBodyLocomotion.AMPHIBIOUS;
        if (navigation instanceof WaterBoundPathNavigation) return MindBodyLocomotion.AQUATIC;
        return MindBodyLocomotion.GROUNDED;
    }

    @Override
    public void attached() {
        if (held) return;
        org.bukkit.entity.LivingEntity entity = (org.bukkit.entity.LivingEntity) mob.getBukkitEntity();
        if (NativeMindBorrowedMarker.held(entity)) {
            throw new IllegalStateException("Another Mind already drives this mob");
        }
        // A receipt nobody holds is from a borrow cut short: what the mob has now is not what it had before.
        NativeMindBorrowedMarker.redeem(entity);
        hadNoAi = mob.isNoAi();
        hadNoGravity = mob.isNoGravity();
        NativeMindBorrowedMarker.hold(entity, plugin, hostIdentity, hadNoAi, hadNoGravity);
        ownBodyRotation = (BodyRotationControl) get(NativeMindBodyControl.BODY_ROTATION_CONTROL);
        set(NativeMindBodyControl.BODY_ROTATION_CONTROL, bodyRotation);
        held = true;
        quietOwnAi();
        mob.setNoAi(true);
    }

    @Override
    public void detached() {
        if (!held) return;
        held = false;
        flightVelocity = null;
        swelling = false;
        mob.getNavigation().stop();
        NativeMindVersion.stopControl(mob.getMoveControl());
        if (mob instanceof Creeper creeper) creeper.setSwellDir(-1);
        set(NativeMindBodyControl.BODY_ROTATION_CONTROL, ownBodyRotation);
        ownBodyRotation = null;
        mob.setNoGravity(hadNoGravity);
        mob.setNoAi(hadNoAi);
        NativeMindBorrowedMarker.letGo((org.bukkit.entity.LivingEntity) mob.getBukkitEntity(), plugin);
    }

    boolean hadNoAi() {
        return hadNoAi;
    }

    /**
     * Stops what the mob's own AI was in the middle of, the way vanilla stops it. Switching the AI off alone would
     * freeze a running goal as it stood, and some keep acting through the mob's own tick: a creeper goes on
     * swelling until it explodes.
     */
    private void quietOwnAi() {
        for (Field field : GOAL_SELECTORS) {
            GoalSelector selector = (GoalSelector) get(field);
            if (selector == null) continue;
            for (WrappedGoal goal : new ArrayList<>(selector.getAvailableGoals())) {
                if (!goal.isRunning()) continue;
                try {
                    goal.stop();
                } catch (RuntimeException ignored) {
                    // Another plugin's goal failing to stop must not keep the Mind off the body.
                }
            }
        }
        stopBrain();
        if (mob instanceof Creeper creeper) creeper.setSwellDir(-1);
        mob.getNavigation().stop();
        NativeMindVersion.stopControl(mob.getMoveControl());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stopBrain() {
        try {
            ((Brain) mob.getBrain()).stopAll((ServerLevel) mob.level(), mob);
        } catch (RuntimeException ignored) {
            // As for goals.
        }
    }

    @Override
    public void beforeMindTick() {
        flightVelocity = null;
        // Something gave the mob its own AI back since the last tick: a release that ended, or another plugin.
        if (!mob.isNoAi()) {
            quietOwnAi();
            mob.setNoAi(true);
        }
        mob.getSensing().tick();
    }

    @Override
    public void afterMindTick() {
        if (mob instanceof Creeper creeper) {
            creeper.setSwellDir(swelling ? 1 : -1);
            swelling = false;
        }
        if (flightVelocity != null) {
            mob.getNavigation().stop();
            NativeMindVersion.stopControl(mob.getMoveControl());
            mob.setNoGravity(true);
            mob.setDeltaMovement(flightVelocity);
            mob.move(MoverType.SELF, flightVelocity);
            mob.fallDistance = 0;
            if (flightVelocity.horizontalDistance() > 1.0E-5D) {
                float desired = (float) Math.toDegrees(Math.atan2(-flightVelocity.x, flightVelocity.z));
                mob.setYRot(mob.getYRot() + Mth.clamp(Mth.wrapDegrees(desired - mob.getYRot()), -8F, 8F));
                mob.setYBodyRot(mob.getYRot());
                mob.setYHeadRot(mob.getYRot());
            }
            return;
        }
        double startX = mob.getX(), startZ = mob.getZ();
        // What Mob.serverAiStep runs after the goals, in its order.
        mob.getNavigation().tick();
        mob.getMoveControl().tick();
        mob.getLookControl().tick();
        mob.getJumpControl().tick();
        // With its AI off the mob's own tick skips travel, as it does for a carrier.
        NativeMindVersion.advancePhysics(mob, new Vec3(mob.xxa, mob.yya, mob.zza));
        double dx = mob.getX() - startX, dz = mob.getZ() - startZ;
        if (dx * dx + dz * dz > NativeMindBodyControl.MOVING_SQUARED) bodyRotation.moved();
    }

    @Override
    public void stopMovement() {
        flightVelocity = null;
        swelling = false;
        mob.getNavigation().stop();
        NativeMindVersion.stopControl(mob.getMoveControl());
        mob.stopInPlace();
    }

    @Override
    public void tickPausedPhysics() {
        beforeMindTick();
        mob.getNavigation().stop();
        NativeMindVersion.stopControl(mob.getMoveControl());
        mob.setXxa(0);
        mob.setYya(0);
        mob.setZza(0);
        if (mob instanceof Creeper creeper) creeper.setSwellDir(-1);
        NativeMindVersion.advancePhysics(mob, Vec3.ZERO);
    }

    /** Gives the mob its own AI until the next Mind tick, which stops whatever it took up and takes it back. */
    @Override
    public void release() {
        stopMovement();
        if (held) mob.setNoAi(hadNoAi);
    }

    @Override
    public void steerFlight(Vec3 velocity) {
        flightVelocity = velocity;
    }

    @Override
    public boolean attack(LivingEntity target) {
        if (!(mob instanceof Creeper)) return false;
        swelling = true;
        return true;
    }

    private Object get(Field field) {
        try {
            return field.get(mob);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not read a borrowed mob's native controls", exception);
        }
    }

    private void set(Field field, Object value) {
        try {
            field.set(mob, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not change a borrowed mob's native controls", exception);
        }
    }

    /** Both of a mob's goal selectors, found by type as the carrier control finds them: their names vary. */
    private static List<Field> goalSelectors() {
        List<Field> fields = new ArrayList<>(2);
        for (Field field : Mob.class.getDeclaredFields()) {
            if (field.getType() != GoalSelector.class || Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            fields.add(field);
        }
        if (fields.isEmpty()) throw new ExceptionInInitializerError("Native goal selectors are unavailable");
        return List.copyOf(fields);
    }
}
