package com.magmaguy.magmacore.ai;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.Optional;
import java.util.UUID;

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

    /** Creates the default grounded hostile carrier. */
    default MobBody spawnBody(Location location) {
        return spawnBody(location, MindBodyProfile.GROUNDED);
    }

    /** Creates a hostile carrier using the requested native movement and physical profile. */
    MobBody spawnBody(Location location, MindBodyProfile profile);

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
