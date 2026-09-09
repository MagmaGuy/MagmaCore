package com.magmaguy.easyminecraftgoals.v1_21_R5.mind;

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
import org.bukkit.craftbukkit.v1_21_R5.CraftWorld;
import org.bukkit.craftbukkit.v1_21_R5.entity.CraftLivingEntity;

/** Native signatures which differ across supported Minecraft adapters. */
final class NativeMindVersion {
    private static final java.lang.reflect.Field MOVE_OPERATION = moveOperation();
    private static final Object WAIT = java.util.Arrays.stream(MOVE_OPERATION.getType().getEnumConstants())
            .filter(value -> ((Enum<?>) value).name().equals("WAIT")).findFirst().orElseThrow();
    private static java.lang.reflect.Field moveOperation() {
        for (java.lang.reflect.Field field : MoveControl.class.getDeclaredFields()) {
            if (!field.getType().isEnum()) continue;
            field.setAccessible(true); return field;
        }
        throw new ExceptionInInitializerError("MoveControl operation is unavailable");
    }
    private NativeMindVersion() { }
    static ServerLevel getServerLevel(Location location) { return ((CraftWorld) location.getWorld()).getHandle(); }
    static net.minecraft.world.entity.LivingEntity getNMSLivingEntity(LivingEntity entity) { return ((CraftLivingEntity) entity).getHandle(); }
    static EntityType<?> entityType(String key) { return BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.ResourceLocation.parse(key)); }
    static void position(Mob mob, double x, double y, double z, float yaw, float pitch) { mob.snapTo(x, y, z, yaw, pitch); }
    static Brain<Mob> newBrain() { return new Brain<>(java.util.List.of(), java.util.List.of(), ImmutableList.of(), () -> Brain.codec(java.util.List.of(), java.util.List.of())); }
    static void addActivity(Brain<Mob> brain, Activity activity,
            ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super Mob>>> controls) {
        brain.addActivity(activity, controls);
    }
    static void stopControl(MoveControl control) { try { MOVE_OPERATION.set(control, WAIT); }
        catch (IllegalAccessException failure) { throw new IllegalStateException("Could not stop native movement", failure); } }
    static boolean sunlightExposure(Mob mob) {
        boolean susceptible = switch (mob.getBukkitEntity().getType().name()) {
            case "ZOMBIE", "ZOMBIE_VILLAGER", "DROWNED", "SKELETON", "STRAY", "BOGGED", "PHANTOM" -> true;
            default -> false;
        };
        return susceptible && mob.level().isBrightOutside()
                && mob.getLightLevelDependentMagicValue() > .5F
                && !mob.isInWaterOrRain() && !mob.isInPowderSnow && !mob.wasInPowderSnow
                && mob.level().canSeeSky(net.minecraft.core.BlockPos.containing(mob.getX(), mob.getEyeY(), mob.getZ()));
    }
}
