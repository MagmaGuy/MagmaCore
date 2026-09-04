package com.magmaguy.magmacore.ai;

import org.bukkit.entity.LivingEntity;

/** Native body owned by MagmaCore and driven by its version-specific AI engine. */
public interface MobBody {
    LivingEntity entity();

    MindBodyProfile profile();

    MindBodyCapabilities capabilities();

    boolean isValid();
}
