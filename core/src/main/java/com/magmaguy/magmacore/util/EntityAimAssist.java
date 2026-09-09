package com.magmaguy.magmacore.util;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Cone acquisition shared by magic weapons and assisted support abilities. */
public final class EntityAimAssist {
    public static final double DEFAULT_CONE_DEGREES = 45D;

    private EntityAimAssist() { }

    /** Priorities sort first, then angular accuracy with a small distance preference. */
    public static <T extends LivingEntity> List<T> select(
            Player owner, Collection<T> nearby, double range, double assistDegrees,
            Predicate<T> eligible, ToIntFunction<T> priority, int limit) {
        if (!Double.isFinite(range) || range <= 0D || limit <= 0) return List.of();
        Location eye = owner.getEyeLocation();
        Vector aim = eye.getDirection();
        if (aim.lengthSquared() < 1.0E-9D) return List.of();
        aim.normalize();
        double minimumDot = Math.cos(Math.toRadians(assistDegrees));
        List<Candidate<T>> candidates = new ArrayList<>();
        for (T target : nearby) {
            if (target == null || target == owner || !target.isValid() || target.isDead()
                    || target.getHealth() <= 0D || target instanceof ArmorStand
                    || !target.getWorld().equals(eye.getWorld())) continue;
            Location center = center(target);
            Vector offset = center.toVector().subtract(eye.toVector());
            double distance = offset.length();
            if (distance <= 1.0E-6D || distance > range) continue;
            double dot = offset.multiply(1D / distance).dot(aim);
            if (dot < minimumDot || !eligible.test(target) || !unobstructed(eye, center)) continue;
            candidates.add(new Candidate<>(target, priority.applyAsInt(target),
                    (1D - dot) * 100D + distance / range, distance));
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt((Candidate<T> candidate) -> candidate.tier())
                        .thenComparingDouble(Candidate::score)
                        .thenComparingDouble(Candidate::distance))
                .limit(limit).map(Candidate::target).toList();
    }

    public static Location center(LivingEntity entity) {
        return entity.getLocation().add(0D, Math.max(.35D, entity.getHeight() * .52D), 0D);
    }

    public static boolean unobstructed(Location source, Location destination) {
        World world = source.getWorld();
        if (world == null || !world.equals(destination.getWorld())) return false;
        Vector delta = destination.toVector().subtract(source.toVector());
        double distance = delta.length();
        if (distance < 1.0E-6D) return true;
        RayTraceResult hit = world.rayTraceBlocks(
                source, delta.normalize(), distance, FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitPosition() == null
                || hit.getHitPosition().distance(source.toVector()) >= distance - .08D;
    }

    private record Candidate<T>(T target, int tier, double score, double distance) { }
}
