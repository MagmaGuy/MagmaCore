package com.magmaguy.magmacore.scripting;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;

import java.util.List;
import java.util.Objects;

record LuaMindModuleSource(
        String sourceName,
        String source,
        String identifier,
        long revision,
        List<String> dependencies) {

    LuaMindModuleSource {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(identifier, "identifier");
        if (revision < 1L) throw new IllegalArgumentException("revision must be positive");
        dependencies = List.copyOf(dependencies);
    }

    static LuaMindModuleSource inspect(String sourceName, String source) {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(source, "source");
        Globals globals = LuaEnvironmentFactory.createGlobals();
        LuaTable table = LuaMindSourceReader.evaluateTable(
                globals,
                sourceName,
                source,
                "module");
        return new LuaMindModuleSource(
                sourceName,
                source,
                LuaMindProgramCompiler.requiredString(table, "id"),
                LuaMindProgramCompiler.requiredPositiveLong(table, "revision"),
                LuaMindSourceReader.stringArray(table.get("dependencies"), "dependencies"));
    }

    LuaTable evaluate(Globals globals) {
        LuaTable table = LuaMindSourceReader.evaluateTable(
                globals,
                sourceName,
                source,
                "module");
        String evaluatedIdentifier = LuaMindProgramCompiler.requiredString(table, "id");
        long evaluatedRevision = LuaMindProgramCompiler.requiredPositiveLong(table, "revision");
        List<String> evaluatedDependencies = LuaMindSourceReader.stringArray(
                table.get("dependencies"),
                "dependencies");
        if (!identifier.equals(evaluatedIdentifier)
                || revision != evaluatedRevision
                || !dependencies.equals(evaluatedDependencies)) {
            throw new IllegalArgumentException(
                    "Mind module " + sourceName + " produced non-deterministic metadata");
        }
        return table;
    }

    LuaMindModuleDescriptor descriptor() {
        return new LuaMindModuleDescriptor(identifier, revision, dependencies);
    }
}
