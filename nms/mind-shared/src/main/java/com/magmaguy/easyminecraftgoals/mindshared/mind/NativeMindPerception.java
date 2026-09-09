package com.magmaguy.easyminecraftgoals.mindshared.mind;

import com.magmaguy.magmacore.ai.MindPerception;
import com.magmaguy.magmacore.ai.MindNavigationStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Optional;

final class NativeMindPerception implements MindPerception {
    private final Mob mob;
    private final NativeMindNavigationTracker navigation;

    NativeMindPerception(Mob mob, NativeMindNavigationTracker navigation) {
        this.mob = mob;
        this.navigation = navigation;
    }

    @Override
    public Optional<LivingEntity> currentTarget() {
        return Optional.ofNullable(mob.getTarget())
                .map(target -> (LivingEntity) target.getBukkitEntity());
    }

    @Override
    public Optional<Player> nearestPlayer(double maxDistance) {
        NativeMindHost.requirePrimaryThread();
        if (!Double.isFinite(maxDistance) || maxDistance <= 0.0D) {
            throw new IllegalArgumentException("maxDistance must be finite and positive");
        }

        double maximumSquared = maxDistance * maxDistance;
        ServerPlayer nearest = null;
        double nearestSquared = maximumSquared;
        for (ServerPlayer player : ((net.minecraft.server.level.ServerLevel) mob.level()).players()) {
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
            double distanceSquared = mob.distanceToSqr(player);
            if (distanceSquared > nearestSquared) continue;
            if (distanceSquared == nearestSquared && nearest != null
                    && player.getUUID().compareTo(nearest.getUUID()) >= 0) continue;
            nearest = player;
            nearestSquared = distanceSquared;
        }
        return nearest == null
                ? Optional.empty()
                : Optional.of(nearest.getBukkitEntity());
    }

    @Override
    public boolean lineOfSight(LivingEntity target) {
        NativeMindHost.requirePrimaryThread();
        if (target == null || target.getWorld() != mob.getBukkitEntity().getWorld()) return false;
        net.minecraft.world.entity.LivingEntity nativeTarget =
                NativeMindVersion.getNMSLivingEntity(target);
        return mob.getSensing().hasLineOfSight(nativeTarget);
    }

    @Override
    public MindNavigationStatus navigation() {
        NativeMindHost.requirePrimaryThread();
        return navigation.status();
    }
}
