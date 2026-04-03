package com.magmaguy.magmacore.scripting;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Base class for anything that can be scripted with Lua.
 * Plugins extend this for their specific entity types.
 */
public abstract class ScriptableEntity {

    /**
     * Build the primary context table entry for this entity.
     * Called lazily on first hook fire.
     */
    public abstract LuaTable buildContextTable(ScriptInstance instance);

    /**
     * The key name used in the Lua context table (e.g., "boss", "prop").
     */
    public abstract String getContextKey();

    /**
     * Returns all hooks this entity type supports.
     */
    public abstract Set<ScriptHook> getSupportedHooks();

    /**
     * The underlying Bukkit entity, if any.
     */
    public abstract Entity getBukkitEntity();

    /**
     * Current location for zone calculations.
     */
    public abstract Location getLocation();

    /**
     * Resolve additional context values beyond standard ones.
     * Return LuaValue.NIL if the key is not recognized.
     */
    public LuaValue resolveExtraContext(String key, ScriptInstance instance) {
        return LuaValue.NIL;
    }

    /**
     * Returns the shared cooldown store for global cooldowns.
     * Override to share across multiple ScriptInstances on the same logical owner
     * (e.g., per-entity for mobs/props, per-player for items).
     * Default returns a per-instance map (no sharing).
     */
    public Map<String, Long> getGlobalCooldownStore() {
        return globalCooldowns;
    }

    private final HashMap<String, Long> globalCooldowns = new HashMap<>();

    /**
     * Called when ScriptInstance is shut down. Override to clean up.
     */
    public void onShutdown() {
    }
}
