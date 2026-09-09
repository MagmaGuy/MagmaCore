package com.magmaguy.easyminecraftgoals.v26.mind;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.schedule.Activity;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import com.magmaguy.easyminecraftgoals.v26.CraftBukkitBridge;

/** Native signatures which differ across supported Minecraft adapters. */
final class NativeMindVersion {
    private NativeMindVersion() { }
    static void advancePhysics(Mob mob, net.minecraft.world.phys.Vec3 input) { mob.travel(input); }
    static ServerLevel getServerLevel(Location location) { return CraftBukkitBridge.getServerLevel(location); }
    static net.minecraft.world.entity.LivingEntity getNMSLivingEntity(LivingEntity entity) { return CraftBukkitBridge.getNMSLivingEntity(entity); }
    static EntityType<?> entityType(String key) { return BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.parse(key)); }
    static void position(Mob mob, double x, double y, double z, float yaw, float pitch) { mob.snapTo(x, y, z, yaw, pitch); }
    static Brain<Mob> newBrain() { return new Brain<>(); }
    static void addActivity(Brain<Mob> brain, Activity activity,
            ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super Mob>>> controls) {
        brain.addActivity(activity, controls, java.util.Set.of(), java.util.Set.of());
    }
    static void stopControl(MoveControl control) { control.setWait(); }
    static boolean sunlightExposure(Mob mob) {
        boolean susceptible = mob.getType().builtInRegistryHolder().is(net.minecraft.tags.EntityTypeTags.BURN_IN_DAYLIGHT);
        return susceptible && mob.level().environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.MONSTERS_BURN, mob.position())
                && mob.getLightLevelDependentMagicValue() > .5F
                && !mob.isInWaterOrRain() && !mob.isInPowderSnow && !mob.wasInPowderSnow
                && mob.level().canSeeSky(net.minecraft.core.BlockPos.containing(mob.getX(), mob.getEyeY(), mob.getZ()));
    }
}
