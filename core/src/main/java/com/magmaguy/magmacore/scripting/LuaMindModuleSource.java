package com.magmaguy.magmacore.scripting;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Prototype;
import org.luaj.vm2.Varargs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** One inspected module: its source, the prototype compiled from it once, and the metadata it declared. */
record LuaMindModuleSource(
        String sourceName,
        String source,
        Prototype prototype,
        String identifier,
        long revision,
        List<String> dependencies,
        Map<String, Object> settings) {

    LuaMindModuleSource {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(prototype, "prototype");
        Objects.requireNonNull(identifier, "identifier");
        if (revision < 1L) throw new IllegalArgumentException("revision must be positive");
        dependencies = List.copyOf(dependencies);
        settings = Map.copyOf(settings);
    }

    static LuaMindModuleSource inspect(String sourceName, String source) {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(source, "source");
        Globals globals = LuaEnvironmentFactory.createGlobals();
        Prototype prototype = LuaMindSourceReader.compile(globals, sourceName, source);
        return LuaMindSourceReader.evaluate(globals, sourceName, prototype, "module", table -> new LuaMindModuleSource(
                sourceName,
                source,
                prototype,
                LuaMindProgramCompiler.requiredString(table, "id"),
                LuaMindProgramCompiler.requiredPositiveLong(table, "revision"),
                LuaMindSourceReader.stringArray(table.get("dependencies"), "dependencies"),
                settings(table.get("settings"), sourceName)));
    }

    LuaTable evaluate(Globals globals) {
        return LuaMindSourceReader.evaluate(globals, sourceName, prototype, "module", table -> {
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
        });
    }

    LuaMindModuleDescriptor descriptor() {
        return new LuaMindModuleDescriptor(identifier, revision, dependencies, settings);
    }

    /**
     * A module's declared setting defaults: numbers, true or false, text, or lists of text. It reads tables raw, so
     * a script's metamethods never decide what a module declares.
     */
    private static Map<String, Object> settings(LuaValue value, String sourceName) {
        if (value.isnil()) return Map.of();
        if (!value.istable()) {
            throw new IllegalArgumentException("Mind module " + sourceName + " settings must be a table of named values");
        }
        LuaTable table = value.checktable();
        Map<String, Object> settings = new TreeMap<>();
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = table.next(key);
            key = next.arg1();
            if (key.isnil()) break;
            if (key.type() != LuaValue.TSTRING) {
                throw new IllegalArgumentException("Mind module " + sourceName + " settings must be named, but entry "
                        + key.tojstring() + " is not");
            }
            settings.put(key.tojstring(), settingValue(sourceName, key.tojstring(), next.arg(2)));
        }
        return settings;
    }

    private static Object settingValue(String sourceName, String name, LuaValue value) {
        return switch (value.type()) {
            case LuaValue.TBOOLEAN -> value.toboolean();
            case LuaValue.TNUMBER -> finiteNumber(sourceName, name, value.todouble());
            case LuaValue.TSTRING -> value.tojstring();
            case LuaValue.TTABLE -> textList(sourceName, name, value.checktable());
            default -> throw new IllegalArgumentException("Mind module " + sourceName + " setting '" + name
                    + "' must be a number, true or false, text, or a list of text");
        };
    }

    private static double finiteNumber(String sourceName, String name, double number) {
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException("Mind module " + sourceName + " setting '" + name
                    + "' must be a finite number");
        }
        return number;
    }

    private static List<String> textList(String sourceName, String name, LuaTable table) {
        // Count the real entries before trusting the border: a sparse table can report a huge raw length.
        int length = table.rawlen();
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
        List<String> values = new ArrayList<>(length);
        for (int index = 1; index <= length; index++) {
            LuaValue entry = table.rawget(index);
            if (entry.type() != LuaValue.TSTRING) {
                throw new IllegalArgumentException("Mind module " + sourceName + " setting '" + name
                        + "' must list only text");
            }
            values.add(entry.tojstring());
        }
        return List.copyOf(values);
    }
}
