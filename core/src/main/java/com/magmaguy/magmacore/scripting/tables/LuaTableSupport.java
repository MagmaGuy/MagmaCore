package com.magmaguy.magmacore.scripting.tables;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;

public final class LuaTableSupport {

    private LuaTableSupport() {}

    public static LuaTable locationToTable(Location location) {
        LuaTable table = new LuaTable();
        table.set("x", location.getX());
        table.set("y", location.getY());
        table.set("z", location.getZ());
        table.set("yaw", location.getYaw());
        table.set("pitch", location.getPitch());
        if (location.getWorld() != null)
            table.set("world", location.getWorld().getName());
        return table;
    }

    public static LuaTable vectorToTable(Vector vector) {
        LuaTable table = new LuaTable();
        table.set("x", vector.getX());
        table.set("y", vector.getY());
        table.set("z", vector.getZ());
        return table;
    }

    public static Location tableToLocation(LuaTable table, World defaultWorld) {
        double x = table.get("x").checkdouble();
        double y = table.get("y").checkdouble();
        double z = table.get("z").checkdouble();
        float yaw = (float) table.get("yaw").optdouble(0);
        float pitch = (float) table.get("pitch").optdouble(0);
        World world = defaultWorld;
        LuaValue worldValue = table.get("world");
        if (worldValue.isstring()) {
            World namedWorld = Bukkit.getWorld(worldValue.tojstring());
            if (namedWorld != null) world = namedWorld;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }

    public static Vector tableToVector(LuaTable table) {
        return new Vector(
            table.get("x").checkdouble(),
            table.get("y").checkdouble(),
            table.get("z").checkdouble()
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
