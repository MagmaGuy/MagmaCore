package com.magmaguy.magmacore.scripting;

/**
 * Represents a named hook that a Lua script can register a handler for.
 * Plugins create their own hook instances.
 */
public final class ScriptHook {
    private final String key;

    public ScriptHook(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    // Common hooks available to all scriptable entities
    public static final ScriptHook ON_SPAWN = new ScriptHook("on_spawn");
    public static final ScriptHook ON_TICK = new ScriptHook("on_game_tick");
    public static final ScriptHook ON_ZONE_ENTER = new ScriptHook("on_zone_enter");
    public static final ScriptHook ON_ZONE_LEAVE = new ScriptHook("on_zone_leave");

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ScriptHook that)) return false;
        return key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "ScriptHook{" + key + "}";
    }
}
