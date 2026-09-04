package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.ai.MindBehavior;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindControl;
import com.magmaguy.magmacore.ai.MindExecutionPolicy;
import com.magmaguy.magmacore.ai.MindMemoryKey;
import com.magmaguy.magmacore.ai.MindPosition;
import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.ai.MindRunawayException;
import com.magmaguy.magmacore.ai.MindRunawayPolicy;
import com.magmaguy.magmacore.ai.MindSchema;
import com.magmaguy.magmacore.ai.MindSensor;
import com.magmaguy.magmacore.ai.MindStopReason;
import com.magmaguy.magmacore.ai.MindValueCodec;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Compiles one declarative Lua table into an immutable Mind program.
 *
 * <p>The {@code budget} table contains soft callback, entity, and server scheduling limits. The
 * {@code runaway} table contains hard {@code cpu_micros} and {@code max_instructions} limits.
 * Existing programs may keep {@code max_instructions} in {@code budget}; new programs should put
 * it in {@code runaway} so the two policies stay visibly separate.</p>
 */
public final class LuaMindProgramCompiler {
    private LuaMindProgramCompiler() {
    }

    public static MindProgram compile(LuaTable table) {
        List<String> declaredModules = LuaMindSourceReader.stringArray(
                table.get("modules"),
                "modules");
        if (!declaredModules.isEmpty()) {
            throw new IllegalArgumentException(
                    "Module-composed Mind programs must be compiled through a module owner");
        }
        return compile(table, List.of());
    }

    static MindProgram compile(LuaTable table, List<LuaTable> moduleTables) {
        requireKind(table, "program");
        String identifier = requiredString(table, "id");
        int namespaceSeparator = identifier.indexOf(':');
        if (namespaceSeparator <= 0) {
            throw new IllegalArgumentException("Mind program id must be namespaced");
        }
        long revision = requiredPositiveLong(table, "revision");
        String defaultNamespace = identifier.substring(0, namespaceSeparator);
        MindExecutionPolicy policy = parseExecutionPolicy(
                table.get("budget"),
                table.get("runaway"));

        Map<String, MemoryBinding<?>> memories = new LinkedHashMap<>();
        Map<String, String> memoryAliases = new LinkedHashMap<>();
        Map<String, String> memoryIdentifiers = new LinkedHashMap<>();
        MindSchema.Builder schema = MindSchema.builder();
        List<Contribution> contributions = contributions(table, moduleTables);
        for (Contribution contribution : contributions) {
            LuaValue memoryValue = contribution.table().get("memories");
            if (memoryValue.isnil()) continue;
            LuaTable memoryTable = memoryValue.checktable();
            LuaValue key = LuaValue.NIL;
            while (true) {
                Varargs next = memoryTable.next(key);
                key = next.arg1();
                if (key.isnil()) break;
                String alias = key.checkjstring();
                String memoryIdentifier = canonicalMemoryIdentifier(defaultNamespace, alias);
                rejectCollision("memory alias", alias, contribution.identifier(), memoryAliases);
                rejectCollision(
                        "memory identifier",
                        memoryIdentifier,
                        contribution.identifier(),
                        memoryIdentifiers);
                MemoryBinding<?> binding = parseMemory(
                        alias,
                        memoryIdentifier,
                        next.arg(2));
                memories.put(alias, binding);
                memories.put(binding.key.identifier(), binding);
                schema.memory(binding.key);
            }
        }

        MindProgram.Builder program = MindProgram.builder(identifier, revision)
                .schema(schema.build())
                .executionPolicy(policy);
        Map<String, String> sensorIdentifiers = new LinkedHashMap<>();
        Map<String, String> behaviorIdentifiers = new LinkedHashMap<>();
        for (Contribution contribution : contributions) {
            for (LuaTable sensor : array(contribution.table().get("sensors"), "sensors")) {
                rejectCollision(
                        "sensor",
                        requiredString(sensor, "id"),
                        contribution.identifier(),
                        sensorIdentifiers);
                program.sensor(parseSensor(sensor, memories, policy));
            }
            for (LuaTable behavior : array(contribution.table().get("behaviors"), "behaviors")) {
                rejectCollision(
                        "behavior",
                        requiredString(behavior, "id"),
                        contribution.identifier(),
                        behaviorIdentifiers);
                program.behavior(parseBehavior(behavior, memories, policy));
            }
        }
        return program.build();
    }

    private static List<Contribution> contributions(
            LuaTable program,
            List<LuaTable> moduleTables) {
        List<Contribution> contributions = new ArrayList<>(moduleTables.size() + 1);
        for (LuaTable module : moduleTables) {
            requireKind(module, "module");
            requiredPositiveLong(module, "revision");
            contributions.add(new Contribution(requiredString(module, "id"), module));
        }
        contributions.add(new Contribution(requiredString(program, "id"), program));
        return List.copyOf(contributions);
    }

    private static void rejectCollision(
            String kind,
            String identifier,
            String contribution,
            Map<String, String> declarations) {
        String previous = declarations.putIfAbsent(identifier, contribution);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "Duplicate Mind " + kind + " '" + identifier + "' in "
                            + previous + " and " + contribution);
        }
    }

    private static MemoryBinding<?> parseMemory(
            String alias,
            String identifier,
            LuaValue descriptorValue) {
        String type;
        boolean persistent;
        if (descriptorValue.isstring()) {
            type = descriptorValue.checkjstring();
            persistent = false;
        } else {
            LuaTable descriptor = descriptorValue.checktable();
            type = requiredString(descriptor, "type");
            persistent = descriptor.get("persistent").optboolean(false);
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "string" -> binding(alias, identifier, String.class, MindValueCodec.STRING, persistent,
                    LuaValue::checkjstring, LuaValue::valueOf);
            case "boolean" -> binding(alias, identifier, Boolean.class, MindValueCodec.BOOLEAN, persistent,
                    LuaValue::checkboolean, LuaValue::valueOf);
            case "integer" -> binding(alias, identifier, Long.class, MindValueCodec.LONG, persistent,
                    LuaValue::checklong, value -> LuaValue.valueOf(value.doubleValue()));
            case "number" -> binding(alias, identifier, Double.class, MindValueCodec.DOUBLE, persistent,
                    LuaValue::checkdouble, LuaValue::valueOf);
            case "uuid" -> binding(alias, identifier, UUID.class, MindValueCodec.UUID, persistent,
                    value -> UUID.fromString(value.checkjstring()), value -> LuaValue.valueOf(value.toString()));
            case "position" -> binding(alias, identifier, MindPosition.class, MindPosition.CODEC, persistent,
                    LuaMindProgramCompiler::toPosition, LuaMindProgramCompiler::fromPosition);
            default -> throw new IllegalArgumentException("Unsupported mind memory type '" + type + "'");
        };
    }

    private static MindSensor parseSensor(
            LuaTable table,
            Map<String, MemoryBinding<?>> memories,
            MindExecutionPolicy policy) {
        requireKind(table, "sensor");
        String identifier = requiredString(table, "id");
        int interval = table.get("interval").optint(1);
        if (interval <= 0) throw new IllegalArgumentException("Sensor interval must be positive");
        LuaFunction sense = requiredFunction(table, "sense");
        return new MindSensor() {
            @Override
            public String identifier() {
                return identifier;
            }

            @Override
            public int intervalTicks() {
                return interval;
            }

            @Override
            public void sense(MindContext context) {
                invoke(policy, () -> sense.call(LuaMindContextTable.build(context, memories)));
            }
        };
    }

    private static MindBehavior parseBehavior(
            LuaTable table,
            Map<String, MemoryBinding<?>> memories,
            MindExecutionPolicy policy) {
        requireKind(table, "behavior");
        String identifier = requiredString(table, "id");
        int priority = table.get("priority").optint(0);
        Set<MindControl> controls = parseControls(table.get("controls"));
        LuaFunction canStart = optionalFunction(table, "can_start");
        LuaFunction canContinue = optionalFunction(table, "can_continue");
        LuaFunction start = optionalFunction(table, "start");
        LuaFunction tick = requiredFunction(table, "tick");
        LuaFunction stop = optionalFunction(table, "stop");

        return new MindBehavior() {
            @Override
            public String identifier() {
                return identifier;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public Set<MindControl> controls() {
                return controls;
            }

            @Override
            public boolean canStart(MindContext context) {
                if (canStart == null) return true;
                return invoke(policy, () -> canStart.call(
                        LuaMindContextTable.build(context, memories))).checkboolean();
            }

            @Override
            public boolean canContinue(MindContext context) {
                if (canContinue == null) return canStart(context);
                return invoke(policy, () -> canContinue.call(
                        LuaMindContextTable.build(context, memories))).checkboolean();
            }

            @Override
            public void start(MindContext context) {
                if (start != null) invoke(policy, () -> start.call(
                        LuaMindContextTable.build(context, memories)));
            }

            @Override
            public void tick(MindContext context) {
                invoke(policy, () -> tick.call(LuaMindContextTable.build(context, memories)));
            }

            @Override
            public void stop(MindContext context, MindStopReason reason) {
                if (stop == null) return;
                invoke(policy, () -> stop.invoke(LuaValue.varargsOf(new LuaValue[]{
                        LuaMindContextTable.build(context, memories),
                        LuaValue.valueOf(reason.name().toLowerCase(Locale.ROOT))
                })).arg1());
            }
        };
    }

    private static MindExecutionPolicy parseExecutionPolicy(
            LuaValue budgetValue,
            LuaValue runawayValue) {
        if (budgetValue.isnil() && runawayValue.isnil()) return MindExecutionPolicy.DEFAULT;
        MindExecutionPolicy defaults = MindExecutionPolicy.DEFAULT;
        LuaTable budget = budgetValue.isnil() ? new LuaTable() : budgetValue.checktable();
        LuaTable runaway = runawayValue.isnil() ? new LuaTable() : runawayValue.checktable();
        LuaValue legacyInstructions = budget.get("max_instructions");
        LuaValue explicitInstructions = runaway.get("max_instructions");
        if (!legacyInstructions.isnil() && !explicitInstructions.isnil()) {
            throw new IllegalArgumentException(
                    "Mind max_instructions must be declared in either budget or runaway, not both");
        }
        long maxInstructions = explicitInstructions.optlong(
                legacyInstructions.optlong(
                        defaults.runawayPolicy().maxInstructionsPerCallback()));
        long maxCpuNanos = microsToNanos(runaway.get("cpu_micros").optlong(
                defaults.runawayPolicy().maxCpuNanosPerCallback() / 1_000L));
        return new MindExecutionPolicy(
                microsToNanos(budget.get("callback_micros").optlong(
                        defaults.maxCallbackNanos() / 1_000L)),
                microsToNanos(budget.get("entity_micros").optlong(
                        defaults.maxEntityNanosPerTick() / 1_000L)),
                microsToNanos(budget.get("server_micros").optlong(
                        defaults.maxServerNanosPerTick() / 1_000L)),
                budget.get("max_callbacks").optint(defaults.maxCallbacksPerEntityTick()),
                budget.get("max_action_requests").optint(
                        defaults.maxActionRequestsPerEntityTick()),
                new MindRunawayPolicy(maxCpuNanos, maxInstructions));
    }

    private static long microsToNanos(long micros) {
        if (micros <= 0 || micros > Long.MAX_VALUE / 1_000L) {
            throw new IllegalArgumentException("Mind microsecond budgets must be positive and finite");
        }
        return micros * 1_000L;
    }

    private static Set<MindControl> parseControls(LuaValue value) {
        if (value.isnil()) return Set.of();
        LuaTable table = value.checktable();
        EnumSet<MindControl> controls = EnumSet.noneOf(MindControl.class);
        for (int index = 1; index <= table.length(); index++) {
            String control = table.get(index).checkjstring().toUpperCase(Locale.ROOT);
            try {
                controls.add(MindControl.valueOf(control));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown mind control '" + control.toLowerCase(Locale.ROOT) + "'");
            }
        }
        return Set.copyOf(controls);
    }

    private static List<LuaTable> array(LuaValue value, String field) {
        if (value.isnil()) return List.of();
        LuaTable table = value.checktable();
        int length = table.length();
        List<LuaTable> values = new ArrayList<>(length);
        for (int index = 1; index <= length; index++) {
            values.add(table.get(index).checktable());
        }
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
        return values;
    }

    static String requiredString(LuaTable table, String field) {
        LuaValue value = table.get(field);
        if (!value.isstring()) throw new IllegalArgumentException("Mind field '" + field + "' must be a string");
        return value.checkjstring();
    }

    static long requiredPositiveLong(LuaTable table, String field) {
        LuaValue value = table.get(field);
        if (!value.isnumber() || value.checklong() <= 0) {
            throw new IllegalArgumentException("Mind field '" + field + "' must be a positive integer");
        }
        return value.checklong();
    }

    private static LuaFunction requiredFunction(LuaTable table, String field) {
        LuaFunction function = optionalFunction(table, field);
        if (function == null) throw new IllegalArgumentException("Mind field '" + field + "' must be a function");
        return function;
    }

    private static LuaFunction optionalFunction(LuaTable table, String field) {
        LuaValue value = table.get(field);
        if (value.isnil()) return null;
        if (!value.isfunction()) throw new IllegalArgumentException("Mind field '" + field + "' must be a function");
        return value.checkfunction();
    }

    static void requireKind(LuaTable table, String kind) {
        LuaValue value = table.get("__mind_kind");
        if (!kind.equals(value.optjstring(null))) {
            throw new IllegalArgumentException("Expected ai." + kind + " { ... }");
        }
    }

    private static String canonicalMemoryIdentifier(String namespace, String alias) {
        return alias.indexOf(':') >= 0 ? alias : namespace + ":" + alias;
    }

    private static <T> MemoryBinding<T> binding(
            String alias,
            String identifier,
            Class<T> type,
            MindValueCodec<T> codec,
            boolean persistent,
            LuaDecoder<T> decoder,
            LuaEncoder<T> encoder) {
        MindMemoryKey<T> key = persistent
                ? MindMemoryKey.persistentKey(identifier, type, codec)
                : MindMemoryKey.transientKey(identifier, type);
        return new MemoryBinding<>(alias, key, decoder, encoder);
    }

    private static MindPosition toPosition(LuaValue value) {
        LuaTable table = value.checktable();
        return new MindPosition(
                table.get("world").checkjstring(),
                table.get("x").checkdouble(),
                table.get("y").checkdouble(),
                table.get("z").checkdouble());
    }

    private static LuaValue fromPosition(MindPosition position) {
        LuaTable table = new LuaTable();
        table.set("world", position.world());
        table.set("x", position.x());
        table.set("y", position.y());
        table.set("z", position.z());
        return table;
    }

    private static <T> T invoke(MindExecutionPolicy policy, LuaCall<T> callback) {
        try {
            return LuaExecutionBudget.run(
                    callback::call,
                    policy.runawayPolicy().maxCpuNanosPerCallback(),
                    policy.runawayPolicy().maxInstructionsPerCallback());
        } catch (LuaExecutionBudget.RunawayLimitExceeded exception) {
            throw new MindRunawayException(exception.getMessage(), exception);
        }
    }

    @FunctionalInterface
    private interface LuaCall<T> {
        T call();
    }

    @FunctionalInterface
    interface LuaDecoder<T> {
        T decode(LuaValue value);
    }

    @FunctionalInterface
    interface LuaEncoder<T> {
        LuaValue encode(T value);
    }

    static final class MemoryBinding<T> {
        final String alias;
        final MindMemoryKey<T> key;
        final LuaDecoder<T> decoder;
        final LuaEncoder<T> encoder;

        private MemoryBinding(
                String alias,
                MindMemoryKey<T> key,
                LuaDecoder<T> decoder,
                LuaEncoder<T> encoder) {
            this.alias = alias;
            this.key = key;
            this.decoder = decoder;
            this.encoder = encoder;
        }
    }

    private record Contribution(String identifier, LuaTable table) {
    }
}
