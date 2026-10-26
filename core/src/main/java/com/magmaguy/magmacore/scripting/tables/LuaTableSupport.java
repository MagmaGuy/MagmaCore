package com.magmaguy.magmacore.scripting.tables;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public final class LuaTableSupport {
    private static final LuaString X = LuaValue.valueOf("x"), Y = LuaValue.valueOf("y"), Z = LuaValue.valueOf("z"),
            YAW = LuaValue.valueOf("yaw"), PITCH = LuaValue.valueOf("pitch"), WORLD = LuaValue.valueOf("world");

    private LuaTableSupport() {}

    /**
     * Registers a lazy field on {@code table}: {@code supplier} is invoked only when
     * Lua actually reads {@code key}, and the value is NOT cached on the table — every
     * read calls the supplier fresh. Use for volatile mutable fields like health that
     * scripts often don't touch on a given tick.
     * <p>
     * Multiple calls share a single {@code __index} metatable per table, so adding more
     * lazy fields is cheap. Eagerly-set keys (via {@code table.set("k", ...)}) take
     * precedence over the lazy supplier with the same name. An {@code __index} the table's
     * metatable already has, such as a Mind context's sections, is kept and answers every
     * key without a lazy field.
     */
    public static void lazyField(LuaTable table, String key, Supplier<LuaValue> supplier) {
        LazyIndex index = LazyIndex.attach(table);
        index.put(key, supplier);
    }

    private static final class LazyIndex extends VarArgFunction {
        // Keyed by the Lua string, so a read never decodes the key into a Java string.
        private final Map<LuaValue, Supplier<LuaValue>> suppliers = new HashMap<>();
        // The __index the metatable had before, a function or a table; nil when it had none.
        private final LuaValue fallback;

        private LazyIndex(LuaValue fallback) {
            this.fallback = fallback;
        }

        static LazyIndex attach(LuaTable table) {
            LuaValue existing = table.getmetatable();
            if (existing instanceof LuaTable mt) {
                LuaValue idx = mt.rawget(LuaValue.INDEX);
                if (idx instanceof LazyIndex li) return li;
                LazyIndex li = new LazyIndex(idx);
                mt.rawset(LuaValue.INDEX, li);
                return li;
            }
            LuaTable mt = new LuaTable();
            LazyIndex li = new LazyIndex(LuaValue.NIL);
            mt.rawset(LuaValue.INDEX, li);
            table.setmetatable(mt);
            return li;
        }

        void put(String key, Supplier<LuaValue> supplier) {
            suppliers.put(LuaValue.valueOf(key), supplier);
        }

        @Override
        public Varargs invoke(Varargs args) {
            LuaValue keyValue = args.arg(2);
            if (keyValue.isstring()) {
                // A number key names the field spelled as that number, as it did when keys were Java strings.
                Supplier<LuaValue> supplier = suppliers.get(
                        keyValue.type() == LuaValue.TSTRING ? keyValue : keyValue.checkstring());
                if (supplier != null) {
                    try {
                        LuaValue v = supplier.get();
                        return v != null ? v : LuaValue.NIL;
                    } catch (Throwable ignored) {
                        return LuaValue.NIL;
                    }
                }
            }
            if (fallback.isfunction()) return fallback.call(args.arg1(), keyValue);
            return fallback.istable() ? fallback.get(keyValue) : LuaValue.NIL;
        }
    }

    /** A fresh x/y/z/yaw/pitch/world table, sized for its six fields; world is left out when there is none. */
    public static LuaTable locationToTable(Location location) {
        World world = location.getWorld();
        return locationToTable(location, world == null ? null : LuaValue.valueOf(world.getName()));
    }

    /**
     * {@link #locationToTable(Location)} with the world name already made a Lua string, for a caller that builds
     * many tables in one world; null leaves world out.
     */
    public static LuaTable locationToTable(Location location, LuaString worldName) {
        LuaTable table = new LuaTable(0, 8);
        table.rawset(X, LuaValue.valueOf(location.getX()));
        table.rawset(Y, LuaValue.valueOf(location.getY()));
        table.rawset(Z, LuaValue.valueOf(location.getZ()));
        table.rawset(YAW, LuaValue.valueOf(location.getYaw()));
        table.rawset(PITCH, LuaValue.valueOf(location.getPitch()));
        if (worldName != null) table.rawset(WORLD, worldName);
        return table;
    }

    public static LuaTable vectorToTable(Vector vector) {
        LuaTable table = new LuaTable(0, 4);
        table.rawset(X, LuaValue.valueOf(vector.getX()));
        table.rawset(Y, LuaValue.valueOf(vector.getY()));
        table.rawset(Z, LuaValue.valueOf(vector.getZ()));
        return table;
    }

    public static Location tableToLocation(LuaTable table, World defaultWorld) {
        double x = table.get(X).checkdouble();
        double y = table.get(Y).checkdouble();
        double z = table.get(Z).checkdouble();
        float yaw = (float) table.get(YAW).optdouble(0);
        float pitch = (float) table.get(PITCH).optdouble(0);
        World world = defaultWorld;
        LuaValue worldValue = table.get(WORLD);
        if (worldValue.isstring()) {
            World namedWorld = Bukkit.getWorld(worldValue.tojstring());
            if (namedWorld != null) world = namedWorld;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }

    public static Vector tableToVector(LuaTable table) {
        return new Vector(
            table.get(X).checkdouble(),
            table.get(Y).checkdouble(),
            table.get(Z).checkdouble()
        );
    }

    public static LuaValue tableMethod(LuaTable owner, LuaCallback callback) {
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return callback.invoke(stripMethodSelf(args, owner));
            }
        };
    }

    public static Varargs stripMethodSelf(Varargs args, LuaTable owner) {
        if (args.narg() == 0 || !args.arg1().raweq(owner)) return args;
        LuaValue[] stripped = new LuaValue[Math.max(0, args.narg() - 1)];
        for (int i = 2; i <= args.narg(); i++) stripped[i - 2] = args.arg(i);
        return LuaValue.varargsOf(stripped);
    }

    @FunctionalInterface
    public interface LuaCallback {
        Varargs invoke(Varargs args);
    }
}
