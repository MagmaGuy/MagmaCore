package com.magmaguy.magmacore.scripting;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

record LuaMindModuleSource(
        String sourceName,
        String source,
        String identifier,
        long revision,
        List<String> dependencies,
        Map<String, Object> settings) {

    LuaMindModuleSource {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(identifier, "identifier");
        if (revision < 1L) throw new IllegalArgumentException("revision must be positive");
        dependencies = List.copyOf(dependencies);
        settings = Map.copyOf(settings);
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
                LuaMindSourceReader.stringArray(table.get("dependencies"), "dependencies"),
                settings(table.get("settings"), sourceName));
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
        return new LuaMindModuleDescriptor(identifier, revision, dependencies, settings);
    }

    /** A module's declared setting defaults: numbers, true or false, text, or lists of text. */
    private static Map<String, Object> settings(LuaValue value, String sourceName) {
        if (value.isnil()) return Map.of();
        LuaTable table = value.checktable();
        Map<String, Object> settings = new TreeMap<>();
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = table.next(key);
            key = next.arg1();
            if (key.isnil()) break;
            if (key.type() != LuaValue.TSTRING) {
                throw new IllegalArgumentException("Mind module " + sourceName + " settings must be named");
            }
            settings.put(key.tojstring(), settingValue(sourceName, key.tojstring(), next.arg(2)));
        }
        return settings;
    }

    private static Object settingValue(String sourceName, String name, LuaValue value) {
        return switch (value.type()) {
            case LuaValue.TBOOLEAN -> value.toboolean();
            case LuaValue.TNUMBER -> value.todouble();
            case LuaValue.TSTRING -> value.tojstring();
            case LuaValue.TTABLE -> textList(sourceName, name, value.checktable());
            default -> throw new IllegalArgumentException("Mind module " + sourceName + " setting '" + name
                    + "' must be a number, true or false, text, or a list of text");
        };
    }

    private static List<String> textList(String sourceName, String name, LuaTable table) {
        int length = table.length();
        List<String> values = new ArrayList<>(length);
        for (int index = 1; index <= length; index++) {
            LuaValue entry = table.get(index);
            if (entry.type() != LuaValue.TSTRING) {
                throw new IllegalArgumentException("Mind module " + sourceName + " setting '" + name
                        + "' must list only text");
            }
            values.add(entry.tojstring());
        }
        int entries = 0;
        LuaValue key = LuaValue.NIL;
        while (true) {
            key = table.next(key).arg1();
            if (key.isnil()) break;
            entries++;
        }
        if (entries != length) {
            throw new IllegalArgumentException("Mind module " + sourceName + " setting '" + name
                    + "' must be a list, not a table of names");
        }
        return List.copyOf(values);
    }
}
