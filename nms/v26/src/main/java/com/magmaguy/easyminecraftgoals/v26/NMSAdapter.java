package com.magmaguy.easyminecraftgoals.v26;

import com.magmaguy.easyminecraftgoals.PathfindingHandle;
import com.magmaguy.easyminecraftgoals.constants.OverridableWanderPriority;
import com.magmaguy.easyminecraftgoals.TransientMovementOverride;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.internal.AbstractWanderBackToPoint;
import com.magmaguy.easyminecraftgoals.internal.FakeItem;
import com.magmaguy.easyminecraftgoals.internal.FakeItemSettings;
import com.magmaguy.easyminecraftgoals.internal.FakeText;
import com.magmaguy.easyminecraftgoals.internal.FakeTextSettings;
import com.magmaguy.easyminecraftgoals.internal.PacketEntityInterface;
import com.magmaguy.easyminecraftgoals.internal.PacketInteractionEntity;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import com.magmaguy.easyminecraftgoals.internal.PacketTextEntity;
import com.magmaguy.easyminecraftgoals.v26.entitydata.BodyRotation;
import com.magmaguy.easyminecraftgoals.v26.flee.TransientFleeGoal;
import com.magmaguy.easyminecraftgoals.v26.hitbox.Hitbox;
import com.magmaguy.easyminecraftgoals.v26.passenger.PassengerOffset;
import com.magmaguy.easyminecraftgoals.v26.massblockedit.MassEditBlocks;
import com.magmaguy.easyminecraftgoals.v26.mind.NativeMindHost;
import com.magmaguy.easyminecraftgoals.v26.move.Move;
import com.magmaguy.easyminecraftgoals.v26.packets.FakeItemImpl;
import com.magmaguy.easyminecraftgoals.v26.packets.FakeTextImpl;
import com.magmaguy.easyminecraftgoals.v26.packets.PacketArmorStandEntity;
import com.magmaguy.easyminecraftgoals.v26.packets.PacketBundle;
import com.magmaguy.easyminecraftgoals.v26.packets.PacketDisplayEntity;
import com.magmaguy.easyminecraftgoals.v26.packets.PacketGenericEntity;
import com.magmaguy.easyminecraftgoals.v26.packets.PacketInteractionListener;
import com.magmaguy.easyminecraftgoals.v26.pathfinding.NativePathfindingGoal;
import com.magmaguy.easyminecraftgoals.v26.wanderbacktopoint.WanderBackToPointBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.numbers.BlankFormat;
import org.bukkit.plugin.Plugin;
import com.magmaguy.easyminecraftgoals.v26.wanderbacktopoint.WanderBackToPointGoal;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboard;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import com.magmaguy.magmacore.ai.MindActionSink;
import com.magmaguy.magmacore.ai.MindFailureListener;
import com.magmaguy.magmacore.ai.MindHost;

import java.util.Objects;
import java.util.Optional;

public class NMSAdapter extends com.magmaguy.easyminecraftgoals.NMSAdapter {

    public NMSAdapter() {
        // Register the real packet-size measurer so the FMM packet sampler reports true
        // serialized bytes instead of a flat estimate. Defensive: if the codec reflection
        // fails at runtime it returns -1 and the sampler falls back to estimating.
        com.magmaguy.easyminecraftgoals.internal.PacketSizeEstimator.setImpl(
                new com.magmaguy.easyminecraftgoals.v26.packets.NmsPacketSizeFunction());
    }

    private PacketInteractionListener packetInteractionListener;
    private NativeMindHost mindHost;

    @Override
    public MindHost createMindHost(
            NamespacedKey hostIdentity,
            MindFailureListener failureListener,
            MindActionSink actionSink) {
        if (mindHost != null) {
            throw new IllegalStateException("This adapter already has a native mind host");
        }
        mindHost = new NativeMindHost(hostIdentity, failureListener, actionSink);
        return mindHost;
    }

    @Override
    public boolean isMindBody(Entity entity) {
        return NativeMindHost.isMindCarrier(entity);
    }

    @Override
    public void shutdownMindHost() {
        if (mindHost == null) return;
        mindHost.shutdown();
        mindHost = null;
    }

    private PathfinderMob getPathfinderMob(Entity entity) {
        net.minecraft.world.entity.Entity nmsEntity = CraftBukkitBridge.getNMSEntity(entity);
        if (nmsEntity instanceof PathfinderMob pathfinderMob)
            return pathfinderMob;
        else
            return null;
    }

    @Override
    public PacketModelEntity createPacketArmorStandEntity(Location location) {
        return new PacketArmorStandEntity(location);
    }

    @Override
    public PacketModelEntity createPacketDisplayEntity(Location location) {
        return new PacketDisplayEntity(location);
    }

    @Override
    public PacketTextEntity createPacketTextArmorStandEntity(Location location) {
        return new PacketArmorStandEntity(location);
    }

    @Override
    public boolean canReach(LivingEntity livingEntity, Location destination) {
        PathfinderMob pathfinderMob = getPathfinderMob(livingEntity);
        if (pathfinderMob == null) return false;
        return Move.canReach(pathfinderMob, destination);
    }

    @Override
    public boolean setCustomHitbox(Entity entity, float width, float height, boolean fixed) {
        if (entity == null) return false;
        net.minecraft.world.entity.Entity nmsEntity = CraftBukkitBridge.getNMSEntity(entity);
        return Hitbox.setCustomHitbox(nmsEntity, width, height, fixed);
    }

    @Override
    public boolean setPassengerOffset(Entity entity, double offsetX, double offsetY, double offsetZ) {
        if (entity == null) return false;
        return PassengerOffset.setPassengerOffset(CraftBukkitBridge.getNMSEntity(entity), offsetX, offsetY, offsetZ);
    }

    @Override
    public float getBodyRotation(Entity entity) {
        net.minecraft.world.entity.Entity nmsEntity = CraftBukkitBridge.getNMSEntity(entity);
        return BodyRotation.getBodyRotation(nmsEntity);
    }

    @Override
    public boolean move(LivingEntity livingEntity, double speedModifier, Location location) {
        net.minecraft.world.entity.LivingEntity nmsLivingEntity = CraftBukkitBridge.getNMSLivingEntity(livingEntity);
        PathfinderMob pathfinderMob;
        if (nmsLivingEntity instanceof PathfinderMob pm)
            pathfinderMob = pm;
        else
            pathfinderMob = null;
        if (!(nmsLivingEntity instanceof Mob)) {
            Bukkit.getLogger().info("[EasyMinecraftPathfinding] Entity type " + livingEntity.getType() + " does not extend Mob and is therefore unable to have goals! It will not be able to pathfind.");
            return false;
        }
        return Move.simpleMove(pathfinderMob, speedModifier, location);
    }

    @Override
    protected Optional<PathfindingHandle> createPathfindingHandle(LivingEntity livingEntity, int priority) {
        PathfinderMob pathfinderMob = getPathfinderMob(livingEntity);
        if (pathfinderMob == null) return Optional.empty();
        NativePathfindingGoal goal = new NativePathfindingGoal(pathfinderMob, livingEntity, priority);
        goal.register();
        return Optional.of(goal);
    }

    @Override
    public boolean removeFreeWill(LivingEntity livingEntity) {
        PathfinderMob pathfinderMob = getPathfinderMob(livingEntity);
        if (pathfinderMob == null) return false;
        pathfinderMob.removeFreeWill();
        return true;
    }

    @Override
    public boolean isPositionEntityTicking(Location location) {
        if (location.getWorld() == null) return false;
        return CraftBukkitBridge.getServerLevel(location).isPositionEntityTicking(
                BlockPos.containing(location.getX(), location.getY(), location.getZ()));
    }

    @Override
    public Optional<TransientMovementOverride> beginFlee(
            LivingEntity livingEntity,
            Location threatLocation,
            double speedModifier) {
        Objects.requireNonNull(livingEntity, "livingEntity");
        Objects.requireNonNull(threatLocation, "threatLocation");
        if (!Double.isFinite(speedModifier) || speedModifier <= 0D) return Optional.empty();
        if (threatLocation.getWorld() == null
                || threatLocation.getWorld() != livingEntity.getWorld()) return Optional.empty();

        if (NativeMindHost.isMindCarrier(livingEntity)) {
            return mindHost == null
                    ? Optional.empty()
                    : mindHost.beginFlee(livingEntity, threatLocation, speedModifier);
        }

        PathfinderMob pathfinderMob = getPathfinderMob(livingEntity);
        if (pathfinderMob == null || pathfinderMob.isNoAi()) return Optional.empty();
        TransientFleeGoal goal = new TransientFleeGoal(
                pathfinderMob, livingEntity.getWorld(), threatLocation, speedModifier);
        if (!goal.canUse()) {
            goal.close();
            return Optional.empty();
        }
        goal.register();
        return Optional.of(goal);
    }

    @Override
    public void doNotMove(LivingEntity livingEntity) {
        PathfinderMob pathfinderMob = getPathfinderMob(livingEntity);
        if (pathfinderMob == null) return;
        Move.doNotMove(pathfinderMob);
    }

    @Override
    public boolean forcedMove(LivingEntity livingEntity, double speedModifier, Location location) {
        net.minecraft.world.entity.LivingEntity nmsLivingEntity = CraftBukkitBridge.getNMSLivingEntity(livingEntity);
        if (!(nmsLivingEntity instanceof Mob mob)) {
            Bukkit.getLogger().info("[EasyMinecraftPathfinding] Entity type " + livingEntity.getType() + " does not extend Mob and is therefore unable to have goals! It will not be able to pathfind.");
            return false;
        }
        return Move.forcedMove(mob, speedModifier, location);
    }

    @Override
    public void universalMove(LivingEntity livingEntity, double speedModifier, Location location) {
        net.minecraft.world.entity.LivingEntity nmsLivingEntity = CraftBukkitBridge.getNMSLivingEntity(livingEntity);
        if (!(nmsLivingEntity instanceof Mob mob)) {
            Bukkit.getLogger().info("[EasyMinecraftPathfinding] Entity type " + livingEntity.getType() + " does not extend Mob and is therefore unable to have goals! It will not be able to pathfind.");
            return;
        }
        Move.universalMove(mob, speedModifier, location);
    }

    @Override
    public AbstractWanderBackToPoint wanderBackToPoint(LivingEntity livingEntity,
                                                       Location blockLocation,
                                                       double maximumDistanceFromPoint,
                                                       int maxDurationTicks,
                                                       OverridableWanderPriority overridableWanderPriority) {
        net.minecraft.world.entity.LivingEntity nmsLivingEntity = CraftBukkitBridge.getNMSLivingEntity(livingEntity);
        PathfinderMob pathfinderMob;
        if (nmsLivingEntity instanceof PathfinderMob pm)
            pathfinderMob = pm;
        else
            pathfinderMob = null;
        if (!(nmsLivingEntity instanceof Mob mob)) {
            Bukkit.getLogger().info("[EasyMinecraftPathfinding] Entity type " + livingEntity.getType() + " does not extend Mob and is therefore unable to have goals! It will not be able to pathfind.");
            return null;
        }
        if (overridableWanderPriority.brain) return new WanderBackToPointBehavior(
                livingEntity,
                mob,
                blockLocation,
                maximumDistanceFromPoint,
                overridableWanderPriority.priority,
                maxDurationTicks);
        else return new WanderBackToPointGoal(
                mob,
                livingEntity,
                pathfinderMob,
                blockLocation,
                maximumDistanceFromPoint,
                overridableWanderPriority.priority,
                maxDurationTicks);
    }

    @Override
    public void setBlockInNativeDataPalette(World world, int x, int y, int z, BlockData blockData, boolean applyPhysics) {
        MassEditBlocks.setBlockInNativeDataPalette(world, x, y, z, blockData, applyPhysics);
    }

    @Override
    public AbstractPacketBundle createPacketBundle() {
        return new PacketBundle();
    }

    @Override
    public boolean hideScoreboardNumbers(org.bukkit.scoreboard.Objective objective) {
        if (objective == null || !(objective.getScoreboard() instanceof CraftScoreboard craftScoreboard))
            return false;
        net.minecraft.world.scores.Objective nmsObjective = craftScoreboard.getHandle().getObjective(objective.getName());
        if (nmsObjective == null) return false;
        nmsObjective.setNumberFormat(BlankFormat.INSTANCE);
        return true;
    }

    @Override
    public PacketEntityInterface createPacketEntity(EntityType entityType, Location location) {
        return new PacketGenericEntity(entityType, location);
    }

    @Override
    public FakeText createFakeText(Location location, FakeTextSettings settings) {
        return new FakeTextImpl(location, settings);
    }

    @Override
    public boolean supportsFakeItems() {
        return true;
    }

    @Override
    public FakeItem createFakeItem(Location location, FakeItemSettings settings) {
        return new FakeItemImpl(location, settings);
    }

    @Override
    public PacketInteractionEntity createPacketInteractionEntity(Location location, float width, float height) {
        return new com.magmaguy.easyminecraftgoals.v26.packets.PacketInteractionEntity(location, width, height);
    }

    @Override
    public void initializePacketInteractionListener(Plugin plugin) {
        packetInteractionListener = new PacketInteractionListener(plugin);
        packetInteractionListener.initialize();
    }

    @Override
    public void shutdownPacketInteractionListener() {
        if (packetInteractionListener != null) {
            packetInteractionListener.shutdown();
            packetInteractionListener = null;
        }
        super.shutdownPacketInteractionListener();
    }
}
