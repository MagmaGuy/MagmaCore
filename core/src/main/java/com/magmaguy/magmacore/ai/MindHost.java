package com.magmaguy.magmacore.ai;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Version-independent seam for one logical mind session. A session may outlive its native body
 * across chunk unloads, carrier replacement and phase changes.
 */
public interface MindHost {
    default MindHandle open(UUID logicalOwner, MindProgram program) {
        return open(logicalOwner, program, MindPersistentState.empty());
    }

    MindHandle open(UUID logicalOwner, MindProgram program, MindPersistentState restoredState);

    MindBodyCapabilities bodyCapabilities();

    /** Creates a carrier using the requested native movement and physical profile. */
    default MobBody spawnBody(Location location, MindBodyProfile profile) {
        return spawnBody(location, profile, body -> { });
    }

    /**
     * Prepares the marked carrier before publishing its CreatureSpawnEvent. The callback may bind
     * its Mind and configure the entity. Rejection or failure closes any attached Mind and removes
     * the unpublished body. Callers retain responsibility for their own external bookkeeping.
     *
     * @throws MindBodyRejectedException when the world refuses the prepared body, for example
     *                                   through a cancelled CreatureSpawnEvent
     */
    MobBody spawnBody(Location location, MindBodyProfile profile, Consumer<MobBody> preparation);

    /**
     * Lends this host a mob it did not make, such as a naturally spawned creeper, as a body a Mind can drive.
     *
     * <p>Nothing changes until a session attaches the body with {@link MindHandle#attachBody}. From then on the mob's
     * own goals and Brain stand still and the session's program is all that moves it, in the way the mob moves:
     * with its own navigation, speed and attacks. When the session lets go, through {@link MindHandle#detachBody},
     * {@link MindHandle#close}, the host shutting down, or the mob dying, unloading or changing world, the mob has
     * its own AI back as it was. Nothing is saved with the mob: a borrowed body is never rehydrated, and a mob
     * found holding the state of a borrow that a crash cut short is given its AI back when it loads.</p>
     *
     * <p>{@link MindHandle#setReleased} hands the mob its own AI for a while without ending the borrow.</p>
     *
     * @throws IllegalArgumentException when the entity is not a mob, is dead, is a Mind carrier, or already lends
     *                                  its AI to a Mind of this or another plugin
     */
    default MobBody borrowBody(LivingEntity mob) {
        throw new UnsupportedOperationException("This Mind host cannot borrow existing mobs");
    }

    /** Whether {@link #borrowBody} would accept the mob now. */
    default boolean canBorrow(LivingEntity mob) {
        return false;
    }

    /**
     * Advances every attached Mind session once on the server thread. The plugin that owns the
     * host owns this clock as well, so MagmaCore does not create a second scheduler behind its
     * caller's lifecycle.
     */
    void tick();

    /**
     * Reattaches the Mind session state stored on a carrier restored by Minecraft. Empty means the
     * entity is not marked as a MagmaCore mind carrier.
     */
    Optional<MindBodyRehydration> rehydrateBody(LivingEntity carrier);
}
