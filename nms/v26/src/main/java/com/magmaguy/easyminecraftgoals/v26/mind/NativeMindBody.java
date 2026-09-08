package com.magmaguy.easyminecraftgoals.v26.mind;

import com.magmaguy.magmacore.ai.MindBodyCapabilities;
import com.magmaguy.magmacore.ai.MindBodyProfile;
import com.magmaguy.magmacore.ai.MobBody;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.bukkit.entity.LivingEntity;

import java.util.Objects;

final class NativeMindBody implements MobBody {
    private final NativeMindHost host;
    private final Mob mob;
    private final MindBodyProfile profile;
    private final NativeMindBodyControl control;
    private NativeMindSession session;

    NativeMindBody(NativeMindHost host, Mob mob, MindBodyProfile profile) {
        this.host = Objects.requireNonNull(host, "host");
        this.mob = Objects.requireNonNull(mob, "mob");
        this.profile = Objects.requireNonNull(profile, "profile");
        control = new NativeMindBodyControl(mob, profile);
    }

    NativeMindHost host() {
        return host;
    }

    Mob mob() {
        return mob;
    }

    ServerLevel level() {
        return (ServerLevel) mob.level();
    }

    void attach(NativeMindSession next) {
        if (session != null) {
            throw new IllegalStateException("Native mind body is already attached");
        }
        session = Objects.requireNonNull(next, "next");
    }

    void detach(NativeMindSession expected) {
        if (session == expected) session = null;
    }

    boolean attached() {
        return session != null;
    }

    void beforeMindTick() {
        control.beforeMindTick();
    }

    void afterMindTick() {
        control.afterMindTick();
    }

    void stopMovement() {
        control.stopMovement();
    }

    void steerFlight(net.minecraft.world.phys.Vec3 velocity) { control.steerFlight(velocity); }

    Entity.RemovalReason removalReason() {
        return mob.getRemovalReason();
    }

    @Override
    public LivingEntity entity() {
        return (LivingEntity) mob.getBukkitEntity();
    }

    @Override
    public MindBodyProfile profile() {
        return profile;
    }

    @Override
    public MindBodyCapabilities capabilities() {
        return host.bodyCapabilities();
    }

    @Override
    public boolean isValid() {
        LivingEntity entity = entity();
        return !mob.isRemoved() && entity.isValid() && !entity.isDead();
    }
}
