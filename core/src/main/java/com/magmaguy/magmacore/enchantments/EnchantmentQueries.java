package com.magmaguy.magmacore.enchantments;

import com.magmaguy.magmacore.scripting.ScriptHook;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Captured, synchronous contributions. Gameplay owners validate and apply the returned numbers. */
public final class EnchantmentQueries {
    public static final String CAPABILITY = "enchantment.numeric_queries.v1";
    private EnchantmentQueries() { }

    /** No gameplay default is imposed here. Hosts select measured limits within the engine ceiling. */
    public record Limits(int maximumQueries, long cpuNanos, long instructions) {
        public Limits {
            if (maximumQueries < 1 || maximumQueries > 128 || cpuNanos < 1 || cpuNanos > 50_000_000L
                    || instructions < 1 || instructions > 250_000L)
                throw new IllegalArgumentException("Invalid enchantment query allowance");
        }
    }

    /** Entity identities supply the ordinary Lua gameplay context in the executing provider. */
    public record Query(EnchantmentProviders.Provider provider, String id, ScriptHook hook,
                        int level, Map<String, Object> input, UUID actor, UUID target) {
        public Query(EnchantmentProviders.Provider provider, String id, ScriptHook hook,
                     int level, Map<String, Object> input) {
            this(provider, id, hook, level, input, null, null);
        }
        public Query {
            Objects.requireNonNull(provider, "provider");
            EnchantmentDefinition.requireId(id);
            requireHook(hook);
            if (!id.startsWith(provider.namespace() + ":") || level < 1)
                throw new IllegalArgumentException("Invalid query identity or level");
            input = copyInput(input);
        }
    }

    public enum Status { OK, UNAVAILABLE, STALE, INVALID, FAILED, QUARANTINED, BUDGET_EXHAUSTED }
    public record Contribution(String id, String hook, Double value) { }
    /** A failed batch exposes no partial contributions. Authored Lua mutations are not rolled back. */
    public record Batch(Status status, List<Contribution> contributions, int completedQueries,
                        long chargedNanos, long instructions) {
        public Batch { contributions = List.copyOf(contributions); }
    }

    public static Batch evaluate(List<Query> requests, Limits limits) {
        EnchantmentProviders.requireServerThread();
        Objects.requireNonNull(limits, "limits");
        if (requests == null || requests.size() > limits.maximumQueries())
            throw new IllegalArgumentException("Too many enchantment queries");
        List<Query> queries = List.copyOf(requests);
        List<Contribution> values = new ArrayList<>();
        long nanos = 0, instructions = 0;
        for (Query query : queries) {
            if (nanos >= limits.cpuNanos() || instructions >= limits.instructions())
                return failed(Status.BUDGET_EXHAUSTED, values.size(), nanos, instructions);
            if (!query.provider().capabilities().contains(CAPABILITY))
                return failed(Status.UNAVAILABLE, values.size(), nanos, instructions);
            var result = EnchantmentProviders.call(query.provider(), EnchantmentProviders.Operation.EVALUATE, Map.of(
                    "kind", CAPABILITY, "id", query.id(), "hook", query.hook().getKey(), "level", query.level(),
                    "input", query.input(), "actor", query.actor() == null ? "" : query.actor().toString(),
                    "target", query.target() == null ? "" : query.target().toString(),
                    "cpuNanos", limits.cpuNanos() - nanos, "instructions", limits.instructions() - instructions));
            if (result.status() != EnchantmentProviders.Status.OK) {
                Status status = switch (result.status()) {
                    case STALE -> Status.STALE;
                    case UNAVAILABLE, INCOMPATIBLE, CONFLICT, UNSUPPORTED -> Status.UNAVAILABLE;
                    case INVALID_REQUEST -> Status.INVALID;
                    default -> Status.FAILED;
                };
                return failed(status, values.size(), nanos, instructions);
            }
            try {
                Map<String, Object> data = result.payload();
                if (!data.keySet().equals(Set.of("kind", "id", "hook", "status", "value", "hasValue", "chargedNanos", "instructions"))
                        || !CAPABILITY.equals(data.get("kind")) || !query.id().equals(data.get("id"))
                        || !query.hook().getKey().equals(data.get("hook"))
                        || !(data.get("chargedNanos") instanceof Long usedNanos) || usedNanos < 0
                        || !(data.get("instructions") instanceof Long usedInstructions) || usedInstructions < 0
                        || !(data.get("hasValue") instanceof Boolean hasValue)
                        || !(data.get("value") instanceof Double value) || !Double.isFinite(value)
                        || !(data.get("status") instanceof String statusText))
                    return failed(Status.FAILED, values.size(), nanos, instructions);
                Status status = Status.valueOf(statusText);
                // Do not let a malformed peer overflow accounting or grant itself another allowance.
                nanos += Math.min(usedNanos, limits.cpuNanos() - nanos);
                instructions += Math.min(usedInstructions, limits.instructions() - instructions);
                if (status != Status.OK) return failed(status, values.size(), nanos, instructions);
                if (nanos >= limits.cpuNanos() || instructions >= limits.instructions())
                    return failed(Status.BUDGET_EXHAUSTED, values.size(), nanos, instructions);
                if (!hasValue && value != 0D) return failed(Status.FAILED, values.size(), nanos, instructions);
                values.add(new Contribution(query.id(), query.hook().getKey(), hasValue ? value : null));
            } catch (IllegalArgumentException malformed) {
                return failed(Status.FAILED, values.size(), nanos, instructions);
            }
        }
        return new Batch(Status.OK, values, values.size(), nanos, instructions);
    }

    private static Batch failed(Status status, int completed, long nanos, long instructions) {
        return new Batch(status, List.of(), completed, nanos, instructions);
    }

    static void requireHook(ScriptHook hook) {
        if (hook == null || !hook.getKey().matches("on_[a-z0-9_]{1,100}") || hook.equals(ScriptHook.ON_TICK)
                || hook.equals(ScriptHook.ON_ZONE_ENTER) || hook.equals(ScriptHook.ON_ZONE_LEAVE))
            throw new IllegalArgumentException("Expected a synchronous query hook");
    }

    static Map<String, Object> copyInput(Map<?, ?> input) {
        Map<String, Object> result = EnchantmentValues.copy(input);
        for (var entry : result.entrySet()) {
            if (!entry.getKey().matches("[a-zA-Z_][a-zA-Z0-9_]{0,127}")
                    || !(entry.getValue() instanceof String || entry.getValue() instanceof Number || entry.getValue() instanceof Boolean))
                throw new IllegalArgumentException("Query inputs must be named scalar snapshots");
        }
        return result;
    }
}
