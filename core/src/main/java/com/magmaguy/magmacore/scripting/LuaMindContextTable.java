package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.ai.MindActionRequest;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindMemoryView;
import com.magmaguy.magmacore.ai.MindNavigationProgress;
import com.magmaguy.magmacore.ai.MindNavigationStatus;
import com.magmaguy.magmacore.ai.MindPosition;
import com.magmaguy.magmacore.scripting.LuaMindProgramCompiler.MemoryBinding;
import com.magmaguy.magmacore.scripting.tables.LuaLivingEntityTable;
import com.magmaguy.magmacore.scripting.tables.LuaTableSupport;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class LuaMindContextTable {
    private static final LuaString TICK = LuaValue.valueOf("tick"), GENERATION = LuaValue.valueOf("generation"),
            ENTITY = LuaValue.valueOf("entity"), MEMORY = LuaValue.valueOf("memory"),
            PERCEPTION = LuaValue.valueOf("perception"), ACTUATOR = LuaValue.valueOf("actuator"),
            ACTIONS = LuaValue.valueOf("actions"), GET = LuaValue.valueOf("get"), SET = LuaValue.valueOf("set"),
            FORGET = LuaValue.valueOf("forget"), CONTAINS = LuaValue.valueOf("contains");
    // Room for tick, generation, a host extension's fields and the sections a callback reads, so a typical callback
    // never grows its table.
    private static final int CONTEXT_SLOTS = 8;

    private LuaMindContextTable() {
    }

    /** {@code memories} maps each declared memory's alias and identifier, as Lua strings, to its binding. */
    static LuaTable build(MindContext context, Map<LuaValue, MemoryBinding<?>> memories) {
        LuaTable table = new LuaTable(0, CONTEXT_SLOTS);
        table.rawset(TICK, LuaValue.valueOf((double) context.gameTick()));
        table.rawset(GENERATION, LuaValue.valueOf((double) context.generation()));
        LuaTable metatable = new LuaTable(0, 1);
        metatable.rawset(LuaValue.INDEX, new Sections(context, memories));
        table.setmetatable(metatable);
        return table;
    }

    /**
     * Builds each callback-scoped API section on first use and then keeps that exact table for the rest of the
     * callback. Mind callbacks frequently need only memory and tick data; deferring the other sections avoids
     * constructing the complete Bukkit entity bridge for every can-start/can-continue probe while retaining snapshot
     * consistency when a script reads a section more than once. A built section is also stored on the context table,
     * so later reads find it without this call; a script that clears the field still reads the same snapshot again.
     */
    private static final class Sections extends TwoArgFunction {
        private final MindContext context;
        private final Map<LuaValue, MemoryBinding<?>> memories;
        private LuaValue entity, memory, perception, actuator, actions;

        private Sections(MindContext context, Map<LuaValue, MemoryBinding<?>> memories) {
            this.context = context;
            this.memories = memories;
        }

        @Override
        public LuaValue call(LuaValue table, LuaValue key) {
            if (key.type() != LuaValue.TSTRING) return NIL;
            LuaValue value;
            try {
                if (key.raweq(MEMORY)) {
                    if (memory == null) memory = memoryTable(context.memory(), memories);
                    value = memory;
                } else if (key.raweq(ENTITY)) {
                    if (entity == null) entity = orNil(LuaLivingEntityTable.build(context.entity()));
                    value = entity;
                } else if (key.raweq(ACTUATOR)) {
                    if (actuator == null) actuator = actuatorTable(context);
                    value = actuator;
                } else if (key.raweq(PERCEPTION)) {
                    if (perception == null) perception = perceptionTable(context);
                    value = perception;
                } else if (key.raweq(ACTIONS)) {
                    if (actions == null) actions = actionsTable(context);
                    value = actions;
                } else {
                    return NIL;
                }
            } catch (Throwable ignored) {
                // As before: a section that fails to build reads as nil, and the next read tries again.
                return NIL;
            }
            if (!value.isnil() && table instanceof LuaTable owner) owner.rawset(key, value);
            return value;
        }

        private static LuaValue orNil(LuaValue value) {
            return value == null ? NIL : value;
        }
    }

    private static LuaTable perceptionTable(MindContext context) {
        LuaTable table = new LuaTable();
        table.set("current_target", LuaTableSupport.tableMethod(table, args ->
                context.perception().currentTarget()
                        .<LuaValue>map(LuaLivingEntityTable::build)
                        .orElse(LuaValue.NIL)));
        table.set("nearest_player", LuaTableSupport.tableMethod(table, args ->
                context.perception().nearestPlayer(args.checkdouble(1))
                        .<LuaValue>map(LuaLivingEntityTable::build)
                        .orElse(LuaValue.NIL)));
        table.set("can_see", LuaTableSupport.tableMethod(table, args -> {
            LivingEntity target = resolveLivingEntity(args.arg1());
            return LuaValue.valueOf(target != null && context.perception().lineOfSight(target));
        }));
        table.set("navigation", LuaTableSupport.tableMethod(table, args ->
                navigationTable(context.perception().navigation())));
        return table;
    }

    private static LuaTable navigationTable(MindNavigationStatus status) {
        LuaTable table = new LuaTable();
        table.set("destination_active", LuaValue.valueOf(status.destinationActive()));
        table.set("navigation_in_progress", LuaValue.valueOf(status.navigationInProgress()));
        table.set("consecutive_stuck_ticks", status.consecutiveStuckTicks());
        table.set("destination", status.destination()
                .<LuaValue>map(LuaMindContextTable::positionTable)
                .orElse(LuaValue.NIL));
        table.set("last_progress", status.lastProgress()
                .<LuaValue>map(LuaMindContextTable::progressTable)
                .orElse(LuaValue.NIL));
        return table;
    }

    private static LuaTable progressTable(MindNavigationProgress progress) {
        LuaTable table = new LuaTable();
        table.set("tick", LuaValue.valueOf((double) progress.gameTick()));
        table.set("position", positionTable(progress.position()));
        return table;
    }

    private static LuaTable positionTable(MindPosition position) {
        return LuaMindProgramCompiler.fromPosition(position);
    }

    private static LuaTable actionsTable(MindContext context) {
        LuaTable table = new LuaTable();
        table.set("request", LuaTableSupport.tableMethod(table, args -> {
            String identifier = args.checkjstring(1);
            Map<String, Object> payload = args.narg() < 2 || args.arg(2).isnil()
                    ? Map.of()
                    : actionPayload(args.checktable(2));
            return LuaValue.valueOf(context.actions()
                    .request(new MindActionRequest(identifier, payload))
                    .name()
                    .toLowerCase(Locale.ROOT));
        }));
        return table;
    }

    private static Map<String, Object> actionPayload(LuaTable table) {
        Map<String, Object> payload = new LinkedHashMap<>();
        LuaValue key = LuaValue.NIL;
        while (true) {
            org.luaj.vm2.Varargs next = table.next(key);
            key = next.arg1();
            if (key.isnil()) break;
            payload.put(key.checkjstring(), actionValue(next.arg(2)));
        }
        return payload;
    }

    private static Object actionValue(LuaValue value) {
        if (value.isboolean()) return value.checkboolean();
        if (value.type() == LuaValue.TSTRING) return value.checkjstring();
        if (value.type() == LuaValue.TNUMBER) {
            double number = value.checkdouble();
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("Mind action numbers must be finite");
            }
            long integral = (long) number;
            if (number >= -0x1.0p63 && number < 0x1.0p63 && number == integral) return Long.valueOf(integral);
            return Double.valueOf(number);
        }
        if (!value.istable()) {
            throw new IllegalArgumentException("Unsupported Lua Mind action payload value");
        }

        LuaTable table = value.checktable();
        LuaValue uuid = table.get("uuid");
        if (uuid.isstring()) return UUID.fromString(uuid.checkjstring());
        LuaValue world = table.get("world");
        LuaValue x = table.get("x");
        LuaValue y = table.get("y");
        LuaValue z = table.get("z");
        if (world.isstring() && x.isnumber() && y.isnumber() && z.isnumber()) {
            return new MindPosition(
                    world.checkjstring(),
                    x.checkdouble(),
                    y.checkdouble(),
                    z.checkdouble());
        }
        throw new IllegalArgumentException(
                "Mind action tables must contain either uuid or world/x/y/z fields");
    }

    private static LuaTable memoryTable(
            MindMemoryView memory,
            Map<LuaValue, MemoryBinding<?>> memories) {
        LuaTable table = new LuaTable(0, 4);
        // Called with a colon or a dot; the methods read their arguments in place rather than copying them.
        table.rawset(GET, new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int first = firstArgument(args, table);
                return encode(memory, requireBinding(memories, args.arg(first)));
            }
        });
        table.rawset(SET, new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int first = firstArgument(args, table);
                MemoryBinding<?> binding = requireBinding(memories, args.arg(first));
                LuaMindContextTable.set(memory, binding, args.arg(first + 1),
                        args.narg() >= first + 2 ? args.checklong(first + 2) : null);
                return NIL;
            }
        });
        table.rawset(FORGET, new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                memory.forget(requireBinding(memories, args.arg(firstArgument(args, table))).key);
                return NIL;
            }
        });
        table.rawset(CONTAINS, new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(memory.contains(
                        requireBinding(memories, args.arg(firstArgument(args, table))).key));
            }
        });
        return table;
    }

    /** Where a method's own arguments start: 2 when it was called with a colon on {@code owner}, else 1. */
    private static int firstArgument(Varargs args, LuaTable owner) {
        return args.narg() > 0 && args.arg1().raweq(owner) ? 2 : 1;
    }

    private static LuaTable actuatorTable(MindContext context) {
        LuaTable table = new LuaTable();
        table.set("move_to", LuaTableSupport.tableMethod(table, args -> {
            Location location = LuaTableSupport.tableToLocation(
                    args.checktable(1), context.entity().getWorld());
            return LuaValue.valueOf(context.actuator().moveTo(location, args.optdouble(2, 1.0)));
        }));
        table.set("stop_moving", LuaTableSupport.tableMethod(table, args -> {
            context.actuator().stopMoving();
            return LuaValue.NIL;
        }));
        table.set("look_at", LuaTableSupport.tableMethod(table, args -> {
            context.actuator().lookAt(LuaTableSupport.tableToLocation(
                    args.checktable(1), context.entity().getWorld()));
            return LuaValue.NIL;
        }));
        table.set("jump", LuaTableSupport.tableMethod(table, args -> {
            context.actuator().jump();
            return LuaValue.NIL;
        }));
        // One tick of flight velocity in blocks per tick; a body flies at most MindActuator.MAX_FLIGHT_SPEED
        // (4 blocks per tick) and scales a faster velocity down to it. Raises an error without the MOVE and LOOK
        // leases or for a non-finite value; false when the body cannot fly or its movement is overridden.
        table.set("steer_flight", LuaTableSupport.tableMethod(table, args -> LuaValue.valueOf(context.actuator()
                .steerFlight(new Vector(args.checkdouble(1), args.checkdouble(2), args.checkdouble(3))))));
        table.set("set_target", LuaTableSupport.tableMethod(table, args -> {
            LivingEntity target = resolveLivingEntity(args.arg1());
            if (target == null) return LuaValue.FALSE;
            context.actuator().setTarget(target);
            return LuaValue.TRUE;
        }));
        table.set("clear_target", LuaTableSupport.tableMethod(table, args -> {
            context.actuator().clearTarget();
            return LuaValue.NIL;
        }));
        table.set("attack", LuaTableSupport.tableMethod(table, args -> {
            LivingEntity target = resolveLivingEntity(args.arg1());
            if (target == null) return LuaValue.FALSE;
            context.actuator().attack(target);
            return LuaValue.TRUE;
        }));
        table.set("stop_all", LuaTableSupport.tableMethod(table, args -> {
            context.actuator().stopAll();
            return LuaValue.NIL;
        }));
        return table;
    }

    private static <T> LuaValue encode(MindMemoryView memory, MemoryBinding<T> binding) {
        T value = memory.get(binding.key).orElse(null);
        return value == null ? LuaValue.NIL : binding.encoder.encode(value);
    }

    @SuppressWarnings("unchecked")
    private static <T> void set(
            MindMemoryView memory,
            MemoryBinding<T> binding,
            LuaValue value,
            Long ttlTicks) {
        T decoded = binding.decoder.decode(value);
        if (ttlTicks == null) memory.set(binding.key, decoded);
        else memory.set(binding.key, decoded, ttlTicks);
    }

    /** The binding a memory name argument names; a number names the memory spelled as that number. */
    private static MemoryBinding<?> requireBinding(
            Map<LuaValue, MemoryBinding<?>> memories,
            LuaValue identifier) {
        LuaString name = identifier.checkstring();
        MemoryBinding<?> binding = memories.get(name);
        if (binding == null) throw new IllegalArgumentException("Undeclared mind memory '" + name.tojstring() + "'");
        return binding;
    }

    private static LivingEntity resolveLivingEntity(LuaValue value) {
        String uuidString;
        if (value.isstring()) {
            uuidString = value.checkjstring();
        } else if (value.istable()) {
            uuidString = value.checktable().get("uuid").checkjstring();
        } else {
            return null;
        }
        Entity entity;
        try {
            entity = Bukkit.getEntity(UUID.fromString(uuidString));
        } catch (IllegalArgumentException exception) {
            return null;
        }
        return entity instanceof LivingEntity living ? living : null;
    }
}
