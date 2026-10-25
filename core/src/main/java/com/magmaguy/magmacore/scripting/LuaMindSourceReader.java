package com.magmaguy.magmacore.scripting;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Prototype;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

final class LuaMindSourceReader {
    private static final AtomicLong COMPILATIONS = new AtomicLong();
    private static final LuaValue UNCHANGED_ERROR_MESSAGE = new OneArgFunction() {
        @Override
        public LuaValue call(LuaValue message) {
            return message;
        }
    };

    private LuaMindSourceReader() {
    }

    /** Compiles one Mind source. The prototype holds no state, so any number of closures can share it. */
    static Prototype compile(Globals globals, String sourceName, String source) {
        COMPILATIONS.incrementAndGet();
        try {
            return globals.compilePrototype(new StringReader(source), sourceName);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** How many Mind sources this JVM has compiled, so tests can tell a cached prototype from a recompile. */
    static long compilations() {
        return COMPILATIONS.get();
    }

    /** Runs a fresh closure over a compiled source, so every evaluation gets its own chunk locals. */
    static LuaTable evaluateTable(
            Globals globals,
            String sourceName,
            Prototype prototype,
            String expectedKind) {
        return evaluate(globals, sourceName, prototype, expectedKind, table -> table);
    }

    /**
     * Runs a fresh closure over a compiled source and reads the table it returns in the same execution budget:
     * reading that table can call the script's metamethods. Inside an existing budget, this shares it.
     */
    static <T> T evaluate(
            Globals globals,
            String sourceName,
            Prototype prototype,
            String expectedKind,
            Function<LuaTable, T> read) {
        Globals environment = isolatedEnvironment(globals);
        return LuaExecutionBudget.run(() -> {
            LuaValue result = new LuaClosure(prototype, environment).call();
            if (!(result instanceof LuaTable table)) {
                throw new IllegalArgumentException(
                        "Mind script " + sourceName + " must return ai."
                                + expectedKind + " { ... }");
            }
            LuaMindProgramCompiler.requireKind(table, expectedKind);
            return read.apply(table);
        });
    }

    /**
     * Each chunk keeps its own top-level globals and reads every other name from the shared evaluation globals.
     * <p>
     * The environment is a {@link Globals} because LuaJ calls the debug library's instruction hook, which
     * {@link LuaExecutionBudget} counts and times scripts with, only for closures whose environment is a Globals,
     * and every closure the chunk creates, Mind callbacks included, inherits that. Under a plain table a loop that
     * never ends would hang the server thread. Those closures would also append an empty stack traceback to every
     * error message unless the environment's thread has an error function, so one returns messages unchanged.
     */
    private static Globals isolatedEnvironment(Globals globals) {
        Globals environment = new Globals();
        environment.debuglib = globals.debuglib;
        environment.running.errorfunc = UNCHANGED_ERROR_MESSAGE;
        LuaTable metatable = new LuaTable();
        metatable.set("__index", globals);
        environment.setmetatable(metatable);
        environment.set("_G", environment);
        return environment;
    }

    static LuaMindProgramMetadata inspectProgram(String sourceName, String source) {
        Globals globals = LuaEnvironmentFactory.createGlobals();
        return evaluate(globals, sourceName, compile(globals, sourceName, source), "program",
                LuaMindSourceReader::programMetadata);
    }

    static LuaMindProgramMetadata programMetadata(LuaTable table) {
        LuaMindProgramCompiler.requireKind(table, "program");
        return new LuaMindProgramMetadata(
                LuaMindProgramCompiler.requiredString(table, "id"),
                LuaMindProgramCompiler.requiredPositiveLong(table, "revision"),
                stringArray(table.get("modules"), "modules"));
    }

    /**
     * Reads a dense array of module identifiers. This can run outside the script's execution budget, so the table is
     * read raw and its metamethods never run. The entries are counted before the raw length sizes anything.
     */
    static List<String> stringArray(LuaValue value, String field) {
        if (value.isnil()) return List.of();
        LuaTable table = value.checktable();
        int length = table.rawlen();
        LuaValue key = LuaValue.NIL;
        int entries = 0;
        while (true) {
            Varargs next = table.next(key);
            key = next.arg1();
            if (key.isnil()) break;
            entries++;
            if (!key.isint() || key.checkint() < 1 || key.checkint() > length) {
                throw new IllegalArgumentException(
                        "Mind field '" + field + "' must be a dense array");
            }
        }
        if (entries != length) {
            throw new IllegalArgumentException(
                    "Mind field '" + field + "' must be a dense array");
        }

        List<String> values = new ArrayList<>(length);
        Set<String> unique = new HashSet<>();
        for (int index = 1; index <= length; index++) {
            LuaValue entry = table.rawget(index);
            if (!entry.isstring()) {
                throw new IllegalArgumentException(
                        "Mind field '" + field + "' must be an array of module identifiers");
            }
            String identifier = entry.checkjstring();
            if (!unique.add(identifier)) {
                throw new IllegalArgumentException(
                        "Mind field '" + field + "' contains duplicate module " + identifier);
            }
            values.add(identifier);
        }
        return List.copyOf(values);
    }
}
