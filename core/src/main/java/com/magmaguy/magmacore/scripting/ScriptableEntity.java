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
     * Whether the scripted owner should keep its runtime alive.
     * <p>
     * Entity-backed owners use their Bukkit entity by default. Non-entity owners
     * such as scripted items can override this and keep ticking while their
     * logical owner is still valid.
     */
    public boolean isScriptOwnerActive() {
        Entity entity = getBukkitEntity();
        return entity != null && !entity.isDead();
    }

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
     * Returns the local cooldown store for a given script definition. Override to
     * persist local cooldowns across {@link ScriptInstance} lifetimes (e.g. items
     * that re-instantiate their ScriptInstance every time they are equipped).
     * Default returns a per-instance map (no persistence beyond the ScriptInstance).
     */
    public Map<String, Long> getLocalCooldownStore(ScriptDefinition definition) {
        return localCooldowns;
    }

    private final HashMap<String, Long> localCooldowns = new HashMap<>();

    /**
     * Called when ScriptInstance is shut down. Override to clean up.
     */
    public void onShutdown() {
    }
}
