package com.magmaguy.easyminecraftgoals.mindshared.mind;

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
    private final NativeMindBodyDriver control;
    private final boolean borrowed;
    private NativeMindSession session;
    private boolean preparing;

    /** A carrier this host made, with a clean slate of native controls. */
    NativeMindBody(NativeMindHost host, Mob mob, MindBodyProfile profile) {
        this.host = Objects.requireNonNull(host, "host");
        this.mob = Objects.requireNonNull(mob, "mob");
        this.profile = Objects.requireNonNull(profile, "profile");
        control = new NativeMindBodyControl(mob, profile);
        borrowed = false;
    }

    private NativeMindBody(NativeMindHost host, Mob mob, NativeMindBorrowedControl control) {
        this.host = Objects.requireNonNull(host, "host");
        this.mob = Objects.requireNonNull(mob, "mob");
        this.profile = control.profile();
        this.control = control;
        borrowed = true;
    }

    /** A mob this host did not make, untouched until a session attaches it and handed back when it lets go. */
    static NativeMindBody borrow(NativeMindHost host, Mob mob, org.bukkit.plugin.Plugin plugin) {
        return new NativeMindBody(host, mob, new NativeMindBorrowedControl(mob, plugin, host.hostIdentity()));
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
        Objects.requireNonNull(next, "next");
        // A borrowed mob is taken over here, and refuses when another Mind holds it.
        control.attached();
        session = next;
        if (borrowed) host.borrowedAttached(this);
    }

    void detach(NativeMindSession expected) {
        if (session != expected) return;
        session = null;
        control.detached();
        if (borrowed) host.borrowedDetached(this);
    }

    /** The session driving this body, null while none does. */
    NativeMindSession session() {
        return session;
    }

    /** Whether a borrowed mob had its AI switched off before it was borrowed; false for a carrier. */
    boolean borrowedWithoutAi() {
        return control instanceof NativeMindBorrowedControl held && held.hadNoAi();
    }

    boolean attack(net.minecraft.world.entity.LivingEntity target) {
        return control.attack(target);
    }

    boolean attached() {
        return session != null;
    }

    void beginPreparation() { preparing = true; }

    void endPreparation() { preparing = false; }

    boolean canAttach() {
        return isValid() || (preparing && !mob.isRemoved() && mob.isAlive());
    }

    void abortPreparation() {
        preparing = false;
        if (session != null) session.close();
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

    void tickPausedPhysics() { control.tickPausedPhysics(); }

    void release() { control.release(); }

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
    public boolean borrowed() {
        return borrowed;
    }

    @Override
    public boolean isValid() {
        LivingEntity entity = entity();
        return !mob.isRemoved() && entity.isValid() && !entity.isDead();
    }
}
