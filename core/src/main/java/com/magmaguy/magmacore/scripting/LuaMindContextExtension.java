package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.ai.MindContext;
import org.luaj.vm2.LuaTable;

/**
 * Adds a host's own fields to the context table each Lua Mind callback receives. Pass a separate extension per
 * {@link LuaMindDefinition#instantiate(LuaMindContextExtension)} call when it carries per-entity state.
 *
 * <p>{@link #extend} runs on the server thread inside the callback's execution budget: its CPU time counts toward the
 * program's runaway {@code cpu_micros} limit, and an exception it throws fails that callback. The built-in fields
 * ({@code tick}, {@code generation}, {@code entity}, {@code memory}, {@code perception}, {@code actuator} and
 * {@code actions}) are reserved and must not be overwritten. Reading a built-in from {@code extend} forces its lazy
 * snapshot.</p>
 */
@FunctionalInterface
public interface LuaMindContextExtension {
    LuaMindContextExtension NONE = (table, context, contribution) -> {
    };

    /**
     * @param table        the callback's context table, before Lua sees it
     * @param context      this callback's Mind context
     * @param contribution the id of the module that declared the behavior or sensor, or the program's own id
     */
    void extend(LuaTable table, MindContext context, String contribution);
}
