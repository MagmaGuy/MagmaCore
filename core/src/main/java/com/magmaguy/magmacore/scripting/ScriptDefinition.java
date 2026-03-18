package com.magmaguy.magmacore.scripting;

import lombok.Getter;
import org.luaj.vm2.*;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

@Getter
public class ScriptDefinition {
    private final String fileName;
    private final File sourceFile;
    private final String source;
    private final int priority;
    private final Set<ScriptHook> hooks;

    public ScriptDefinition(String fileName, File sourceFile, String source,
                            int priority, Set<ScriptHook> hooks) {
        this.fileName = fileName;
        this.sourceFile = sourceFile;
        this.source = source;
        this.priority = priority;
        this.hooks = Set.copyOf(hooks);
    }

    public static ScriptDefinition validate(String fileName, File sourceFile,
                                            String source, ScriptProvider provider) {
        Globals globals = LuaEnvironmentFactory.createGlobals();
        LuaTable scriptTable = evaluate(fileName, source, globals);

        int apiVersion = extractIntField(scriptTable, "api_version", fileName, true);
        if (apiVersion != 1)
            throw new IllegalArgumentException(
                "Script " + fileName + " has unsupported api_version " + apiVersion + ". Expected 1.");

        int priority = extractIntField(scriptTable, "priority", fileName, false);

        Set<ScriptHook> hooks = new HashSet<>();
        LuaValue currentKey = LuaValue.NIL;
        while (true) {
            Varargs next = scriptTable.next(currentKey);
            currentKey = next.arg1();
            if (currentKey.isnil()) break;
            String key = currentKey.checkjstring();
            if ("api_version".equals(key) || "priority".equals(key)) continue;

            ScriptHook hook = provider.resolveHook(key);
            if (hook == null)
                throw new IllegalArgumentException(
                    "Script " + fileName + " contains unsupported key '" + key + "'.");
            if (!(scriptTable.get(key) instanceof LuaFunction))
                throw new IllegalArgumentException(
                    "Script " + fileName + " key '" + key + "' must be a function.");
            hooks.add(hook);
        }

        return new ScriptDefinition(fileName, sourceFile, source, priority, hooks);
    }

    public LuaTable instantiate() {
        return evaluate(fileName, source, LuaEnvironmentFactory.createGlobals());
    }

    public boolean supportsHook(ScriptHook hook) {
        return hook != null && hooks.contains(hook);
    }

    private static LuaTable evaluate(String fileName, String source, Globals globals) {
        LuaValue chunk = globals.load(source, fileName);
        LuaValue result = chunk.call();
        if (!(result instanceof LuaTable scriptTable))
            throw new IllegalArgumentException("Script " + fileName + " must return a table.");
        return scriptTable;
    }

    private static int extractIntField(LuaTable table, String key,
                                       String fileName, boolean required) {
        LuaValue value = table.get(key);
        if (value.isnil()) {
            if (required)
                throw new IllegalArgumentException(
                    "Script " + fileName + " is missing required field '" + key + "'.");
            return 0;
        }
        if (!value.isnumber())
            throw new IllegalArgumentException(
                "Script " + fileName + " field '" + key + "' must be a number.");
        return value.toint();
    }
}
