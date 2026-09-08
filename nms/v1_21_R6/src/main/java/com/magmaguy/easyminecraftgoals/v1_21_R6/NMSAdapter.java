package com.magmaguy.easyminecraftgoals.v1_21_R6;

import com.magmaguy.easyminecraftgoals.PathfindingHandle;
import com.magmaguy.easyminecraftgoals.constants.OverridableWanderPriority;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.internal.AbstractWanderBackToPoint;
import com.magmaguy.easyminecraftgoals.internal.FakeItem;
import com.magmaguy.easyminecraftgoals.internal.FakeItemSettings;
import com.magmaguy.easyminecraftgoals.internal.FakeText;
import com.magmaguy.easyminecraftgoals.internal.FakeTextSettings;
import com.magmaguy.easyminecraftgoals.internal.PacketInteractionEntity;
import com.magmaguy.easyminecraftgoals.internal.PacketTextEntity;
import com.magmaguy.easyminecraftgoals.v1_21_R6.packets.FakeItemImpl;
import com.magmaguy.easyminecraftgoals.v1_21_R6.packets.FakeTextImpl;
import com.magmaguy.easyminecraftgoals.v1_21_R6.massblockedit.MassEditBlocks;
import com.magmaguy.easyminecraftgoals.v1_21_R6.move.Move;
import com.magmaguy.easyminecraftgoals.v1_21_R6.entitydata.BodyRotation;
import com.magmaguy.easyminecraftgoals.v1_21_R6.hitbox.Hitbox;
import com.magmaguy.easyminecraftgoals.v1_21_R6.passenger.PassengerOffset;
import com.magmaguy.easyminecraftgoals.internal.PacketEntityInterface;
import com.magmaguy.easyminecraftgoals.v1_21_R6.packets.PacketArmorStandEntity;
import com.magmaguy.easyminecraftgoals.v1_21_R6.packets.PacketBundle;
import com.magmaguy.easyminecraftgoals.v1_21_R6.packets.PacketDisplayEntity;
import com.magmaguy.easyminecraftgoals.v1_21_R6.packets.PacketGenericEntity;
import com.magmaguy.easyminecraftgoals.v1_21_R6.packets.PacketInteractionListener;
import com.magmaguy.easyminecraftgoals.v1_21_R6.pathfinding.NativePathfindingGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.numbers.BlankFormat;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import com.magmaguy.easyminecraftgoals.v1_21_R6.wanderbacktopoint.WanderBackToPointBehavior;
import com.magmaguy.easyminecraftgoals.v1_21_R6.wanderbacktopoint.WanderBackToPointGoal;
import net.minecraft.world.entity.PathfinderMob;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.v1_21_R6.CraftWorld;
import org.bukkit.craftbukkit.v1_21_R6.entity.CraftEntity;
import org.bukkit.craftbukkit.v1_21_R6.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.v1_21_R6.scoreboard.CraftScoreboard;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.Optional;

public class NMSAdapter extends com.magmaguy.easyminecraftgoals.NMSAdapter {
    @Override
    public float getConsumptionSeconds(org.bukkit.inventory.ItemStack item) {
        var nativeItem = org.bukkit.craftbukkit.v1_21_R6.inventory.CraftItemStack.asNMSCopy(item);
        var consumable = nativeItem.get(net.minecraft.core.component.DataComponents.CONSUMABLE);
        return consumable == null ? -1F : consumable.consumeSeconds();
    }

    @Override
    public org.bukkit.inventory.ItemStack withConsumptionSeconds(org.bukkit.inventory.ItemStack item, float seconds) {
        if (!Float.isFinite(seconds) || seconds < 0F)
            throw new IllegalArgumentException("Consumption duration must be finite and non-negative");
        var nativeItem = org.bukkit.craftbukkit.v1_21_R6.inventory.CraftItemStack.asNMSCopy(item);
        var original = nativeItem.get(net.minecraft.core.component.DataComponents.CONSUMABLE);
        if (original == null) throw new IllegalArgumentException("Item is not consumable");
        nativeItem.set(net.minecraft.core.component.DataComponents.CONSUMABLE,
                new net.minecraft.world.item.component.Consumable(seconds, original.animation(), original.sound(),
                        original.hasConsumeParticles(), original.onConsumeEffects()));
        return org.bukkit.craftbukkit.v1_21_R6.inventory.CraftItemStack.asBukkitCopy(nativeItem);
    }


    @Override
    public void damageWithoutCooldown(LivingEntity target, double amount, Entity source) {
        if (!Double.isFinite(amount) || amount < 0D || amount > Float.MAX_VALUE)
            throw new IllegalArgumentException("Damage must be finite and fit a native float");
        var victim = ((CraftLivingEntity) target).getHandle();
        if (victim.generation) throw new IllegalStateException("Cannot damage an entity during world generation");
        var attacker = ((CraftEntity) source).getHandle();
        var sources = victim.damageSources();
        net.minecraft.world.damagesource.DamageSource original;
        if (attacker instanceof net.minecraft.world.entity.projectile.AbstractArrow arrow)
            original = sources.arrow(arrow, arrow.getOwner());
        else if (attacker instanceof net.minecraft.world.entity.player.Player player)
            original = sources.playerAttack(player);
        else if (attacker instanceof net.minecraft.world.entity.LivingEntity living)
            original = sources.mobAttack(living);
        else throw new IllegalArgumentException("Damage source must be a living attacker or arrow");
        var damage = new net.minecraft.world.damagesource.DamageSource(
                original.typeHolder(), original.getDirectEntity(), original.getEntity(), original.sourcePositionRaw()) {
            @Override
            public boolean is(net.minecraft.tags.TagKey<net.minecraft.world.damagesource.DamageType> tag) {
                return tag.equals(net.minecraft.tags.DamageTypeTags.BYPASSES_COOLDOWN) || super.is(tag);
            }
        };
        victim.hurtServer((net.minecraft.server.level.ServerLevel) victim.level(), damage, (float) amount);
    }

    private PacketInteractionListener packetInteractionListener;

    private PathfinderMob getPathfinderMob(Entity entity) {
        if (((CraftEntity) entity).getHandle() instanceof PathfinderMob pathfinderMob1)
            return pathfinderMob1;
        else
            return null;
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
        return ((CraftWorld) location.getWorld()).getHandle().isPositionEntityTicking(
                BlockPos.containing(location.getX(), location.getY(), location.getZ()));
    }

    @Override
    public PacketArmorStandEntity createPacketArmorStandEntity(Location location){
        return new PacketArmorStandEntity(location);
    }

    @Override
    public PacketDisplayEntity createPacketDisplayEntity(Location location){
        return new PacketDisplayEntity(location);
    }

    @Override
    public PacketTextEntity createPacketTextArmorStandEntity(Location location){
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
        return Hitbox.setCustomHitbox(((CraftEntity) entity).getHandle(), width, height, fixed);
    }

    @Override
    public boolean setPassengerOffset(Entity entity, double offsetX, double offsetY, double offsetZ) {
        if (entity == null) return false;
        return PassengerOffset.setPassengerOffset(((CraftEntity) entity).getHandle(), offsetX, offsetY, offsetZ);
    }

    @Override
    public float getBodyRotation(Entity entity) {
        return BodyRotation.getBodyRotation(((CraftEntity) entity).getHandle());
    }

    @Override
    public boolean move(LivingEntity livingEntity, double speedModifier, Location location) {
        PathfinderMob pathfinderMob;
        if (((CraftLivingEntity) livingEntity).getHandle() instanceof PathfinderMob pathfinderMob1)
            pathfinderMob = pathfinderMob1;
        else
            pathfinderMob = null;
        if (!(((CraftLivingEntity) livingEntity).getHandle() instanceof net.minecraft.world.entity.Mob mob)) {
            Bukkit.getLogger().info("[EasyMinecraftPathfinding] Entity type " + livingEntity.getType() + " does not extend Mob and is therefore unable to have goals! It will not be able to pathfind.");
            return false;
        }
        return Move.simpleMove(pathfinderMob, speedModifier, location);
    }

    @Override
    public void doNotMove(LivingEntity livingEntity) {
        PathfinderMob pathfinderMob = getPathfinderMob(livingEntity);
        if (pathfinderMob == null) return;
        Move.doNotMove(pathfinderMob);
    }

    @Override
    public boolean forcedMove(LivingEntity livingEntity, double speedModifier, Location location) {
        if (!(((CraftLivingEntity) livingEntity).getHandle() instanceof net.minecraft.world.entity.Mob mob)) {
            Bukkit.getLogger().info("[EasyMinecraftPathfinding] Entity type " + livingEntity.getType() + " does not extend Mob and is therefore unable to have goals! It will not be able to pathfind.");
            return false;
        }
        return Move.forcedMove(mob, speedModifier, location);
    }

    @Override
    public void universalMove(LivingEntity livingEntity, double speedModifier, Location location) {
        if (!(((CraftLivingEntity) livingEntity).getHandle() instanceof net.minecraft.world.entity.Mob mob)) {
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
        PathfinderMob pathfinderMob;
        if (((CraftLivingEntity) livingEntity).getHandle() instanceof PathfinderMob pathfinderMob1)
            pathfinderMob = pathfinderMob1;
        else
            pathfinderMob = null;
        if (!(((CraftLivingEntity) livingEntity).getHandle() instanceof net.minecraft.world.entity.Mob mob)) {
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
    public AbstractPacketBundle createPacketBundle(){
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
        return new com.magmaguy.easyminecraftgoals.v1_21_R6.packets.PacketInteractionEntity(location, width, height);
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
