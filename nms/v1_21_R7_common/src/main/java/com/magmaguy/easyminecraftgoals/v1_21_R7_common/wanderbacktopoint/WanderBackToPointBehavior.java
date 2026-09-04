package com.magmaguy.easyminecraftgoals.v1_21_R7_common.wanderbacktopoint;

import com.google.common.collect.ImmutableList;
import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.AbstractWanderBackToPoint;
import com.magmaguy.easyminecraftgoals.internal.WanderBackToPointState;
import com.magmaguy.easyminecraftgoals.utils.Utils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.pathfinder.Path;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;

public class WanderBackToPointBehavior extends Behavior<LivingEntity> implements AbstractWanderBackToPoint {

    private Location returnLocation;
    private final double maximumDistanceFromPoint;
    private final org.bukkit.entity.LivingEntity livingEntity;
    private final Mob mob;
    private final int maxDurationTicks;
    private final WanderBackToPointState returnState = new WanderBackToPointState(this);
    private long lastTime;
    private int priority;
    private float speed;
    private int stopReturnDistance = 0;
    //Note: This assumes 20 tps
    private int goalRefreshCooldownTicks = 3 * 20;
    private boolean hardObjective = false;
    private boolean returnDuringCombat = false;
    private boolean teleportOnFail = false;
    private boolean startWithCooldown = false;

    private Path path = null;

    public WanderBackToPointBehavior(org.bukkit.entity.LivingEntity livingEntity,
                                     Mob mob,
                                     Location location,
                                     double maximumDistanceFromPoint,
                                     int priority,
                                     int maxDurationTicks) {
        //Memory status REGISTERED means that the module will always apply, overriding existing values
        super(Map.of(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED), maxDurationTicks, maxDurationTicks);
        this.livingEntity = livingEntity;
        this.mob = mob;
        this.returnLocation = location;
        this.maximumDistanceFromPoint = maximumDistanceFromPoint;
        this.maxDurationTicks = maxDurationTicks;
        this.priority = priority;
        this.lastTime = 0;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel var0, LivingEntity nmsLivingEntity) {
        if (!hardObjective && !returnDuringCombat && mob.getTarget() instanceof Player) {
            updateCooldown();
            return false;
        }
        if ((lastTime + 50L * goalRefreshCooldownTicks) - System.currentTimeMillis() > 0) return false;
        updateCooldown();
        if (Utils.distanceShorterThan(returnLocation.toVector(), livingEntity.getLocation().toVector(), maximumDistanceFromPoint))
            return false;
        path = ((PathfinderMob) nmsLivingEntity).getNavigation().createPath(returnLocation.getX(), returnLocation.getY(), returnLocation.getZ(), stopReturnDistance);
        if (!returnState.begin()) {
            path = null;
            return false;
        }
        if (path != null && path.canReach()) return true;

        path = null;
        returnState.end(true);
        return false;
    }

    @Override
    protected void start(ServerLevel var0, LivingEntity var1, long var2) {
        if (!returnState.isActive() || path == null) return;
        this.mob.getNavigation().stop();
        if (!this.mob.getNavigation().moveTo(path, speed)) {
            returnState.end(true);
            return;
        }
        mob.getBrain().setActiveActivityIfPossible(Activity.CORE);
        if (hardObjective || returnDuringCombat) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!returnState.isActive()) {
                        cancel();
                        return;
                    }
                    if (!livingEntity.isValid() || livingEntity.isDead()) {
                        returnState.end(false);
                        cancel();
                        return;
                    }
                    if (returnState.hasTimedOut() || path == null || !path.canReach() || mob.getNavigation().isDone()) {
                        mob.getNavigation().stop();
                        returnState.end(!returnState.isAtReturnPoint());
                        cancel();
                        return;
                    }
                    mob.getNavigation().moveTo(path, speed);
                }
            }.runTaskTimer(NMSManager.pluginProvider, 0, 1);
        }
    }

    @Override
    protected void stop(ServerLevel var0, LivingEntity var1, long var2) {
        boolean teleportOnFailure = returnState.isActive() && !returnState.isAtReturnPoint();
        mob.getNavigation().stop();
        path = null;
        returnState.end(teleportOnFailure);
        updateCooldown();
        mob.setAggressive(true);
    }

    @Override
    protected boolean canStillUse(ServerLevel var0, LivingEntity var1, long var2) {
        if (!returnState.isActive() || !livingEntity.isValid() || livingEntity.isDead()) return false;
        if (returnState.hasTimedOut() || path == null || !path.canReach()) return false;
        if (!hardObjective && !returnDuringCombat && mob.getTarget() instanceof Player) return false;
        return !path.isDone() && !mob.getNavigation().isDone();
    }

    @Override
    public double getMaximumDistanceFromPoint() {
        return maximumDistanceFromPoint;
    }

    @Override
    public long getLastTime() {
        return lastTime;
    }

    @Override
    public org.bukkit.entity.LivingEntity getLivingEntity() {
        return livingEntity;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public int getMaxDurationTicks() {
        return maxDurationTicks;
    }

    @Override
    public float getSpeed() {
        return speed;
    }

    @Override
    public AbstractWanderBackToPoint setSpeed(float speed) {
        this.speed = speed;
        return this;
    }

    @Override
    public Location getReturnLocation() {
        return returnLocation;
    }

    @Override
    public AbstractWanderBackToPoint setReturnLocation(Location location) {
        this.returnLocation = location.clone();
        return this;
    }

    @Override
    public int getStopReturnDistance() {
        return stopReturnDistance;
    }

    @Override
    public AbstractWanderBackToPoint setStopReturnDistance(int distance) {
        this.stopReturnDistance = distance;
        return this;
    }

    @Override
    public int getGoalRefreshCooldownTicks() {
        return goalRefreshCooldownTicks;
    }

    @Override
    public AbstractWanderBackToPoint setGoalRefreshCooldownTicks(int ticks) {
        this.goalRefreshCooldownTicks = ticks;
        return this;
    }

    @Override
    public boolean isHardObjective() {
        return hardObjective;
    }

    @Override
    public AbstractWanderBackToPoint setHardObjective(boolean hardObjective) {
        this.priority = -1;
        this.hardObjective = hardObjective;
        return this;
    }

    @Override
    public boolean isReturnDuringCombat() {
        return returnDuringCombat;
    }

    @Override
    public AbstractWanderBackToPoint setReturnDuringCombat(boolean returnDuringCombat) {
        if (returnDuringCombat) this.priority = -1;
        this.returnDuringCombat = returnDuringCombat;
        return this;
    }

    @Override
    public boolean isTeleportOnFail() {
        return teleportOnFail;
    }

    @Override
    public AbstractWanderBackToPoint setTeleportOnFail(boolean teleportOnFail) {
        this.teleportOnFail = teleportOnFail;
        return this;
    }

    @Override
    public boolean isStartWithCooldown() {
        return startWithCooldown;
    }

    @Override
    public AbstractWanderBackToPoint setStartWithCooldown(boolean startWithCooldown) {
        this.startWithCooldown = startWithCooldown;
        return this;
    }

    @Override
    public void updateCooldown() {
        this.lastTime = System.currentTimeMillis();
    }

    @Override
    public void register() {
        if (startWithCooldown) updateCooldown();
        mob.getBrain().addActivity(Activity.CORE, priority, ImmutableList.of(this));
    }

    @Override
    public void unregister() {
        //todo: unfortunately resetting brains is significantly harder than goals, this may be implemented later
    }
}
