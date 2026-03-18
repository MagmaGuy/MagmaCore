package com.magmaguy.magmacore.scripting;

import java.nio.file.Path;

/**
 * Plugins implement this to tell the Lua engine where their scripts live.
 */
public interface ScriptProvider {
    String getNamespace();
    Path getScriptDirectory();
    ScriptHook resolveHook(String key);
}
