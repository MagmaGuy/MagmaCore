package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.ai.MindContext;
import org.luaj.vm2.LuaTable;

/**
 * Adds a host's own fields to the context table each Lua Mind callback receives. One extension serves one program
 * instance, so it can carry the state of the entity that instance drives.
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
