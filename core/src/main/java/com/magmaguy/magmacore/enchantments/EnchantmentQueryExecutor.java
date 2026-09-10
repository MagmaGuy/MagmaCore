package com.magmaguy.magmacore.enchantments;

import com.magmaguy.magmacore.scripting.*;
import com.magmaguy.magmacore.scripting.tables.LuaEntityTable;
import com.magmaguy.magmacore.scripting.tables.LuaLivingEntityTable;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import static com.magmaguy.magmacore.enchantments.EnchantmentQueries.Status;

/** Provider-owned numeric hooks use ordinary Lua capabilities and close their VM after returning. */
final class EnchantmentQueryExecutor {
    private EnchantmentCatalog catalog;
    private final Set<ScriptHook> hooks;
    private final Consumer<String> warning;
    private final Set<String> capabilities;
    private final Set<String> quarantined = new HashSet<>();
    private final Set<String> warned = new HashSet<>();
    private boolean closed;

    EnchantmentQueryExecutor(EnchantmentCatalog catalog, Set<ScriptHook> hooks, Set<String> capabilities, Consumer<String> warning) {
        this.hooks = Set.copyOf(hooks);
        this.hooks.forEach(EnchantmentQueries::requireHook);
        this.warning = warning;
        this.capabilities = Set.copyOf(capabilities);
        validate(catalog);
        this.catalog = catalog;
    }

    void validate(EnchantmentCatalog candidate) {
        for (String id : candidate.definitions().keySet()) {
            ScriptDefinition script = candidate.script(id).orElseThrow();
            if (!hooks.containsAll(script.getHooks()))
                throw new IllegalArgumentException("Catalog declares unsupported query hooks: " + id);
        }
    }

    void reload(EnchantmentCatalog candidate) {
        catalog = candidate;
        quarantined.clear();
        warned.clear();
    }

    void close() { closed = true; quarantined.clear(); warned.clear(); }

    Map<String, Object> evaluate(Map<String, Object> request) {
        if (!request.keySet().equals(Set.of("kind", "id", "hook", "level", "input", "actor", "target", "cpuNanos", "instructions"))
                || !EnchantmentQueries.CAPABILITY.equals(request.get("kind"))
                || !(request.get("id") instanceof String id) || !(request.get("hook") instanceof String hookName)
                || !(request.get("level") instanceof Integer level) || !(request.get("input") instanceof Map<?, ?> rawInput)
                || !(request.get("actor") instanceof String actorId) || !(request.get("target") instanceof String targetId)
                || !(request.get("cpuNanos") instanceof Long nanos) || !(request.get("instructions") instanceof Long instructions))
            throw new IllegalArgumentException("Invalid numeric query payload");
        new EnchantmentQueries.Limits(1, nanos, instructions);
        EnchantmentDefinition.requireId(id);
        ScriptHook hook = new ScriptHook(hookName);
        EnchantmentQueries.requireHook(hook);
        Map<String, Object> input = EnchantmentQueries.copyInput(rawInput);
        Entity actor = resolveEntity(actorId);
        Entity target = resolveEntity(targetId);
        if ((!actorId.isEmpty() && actor == null) || (!targetId.isEmpty() && target == null))
            return response(id, hookName, Status.UNAVAILABLE, null, 0, 0);
        if (closed) return response(id, hookName, Status.UNAVAILABLE, null, 0, 0);
        var definition = catalog.definitions().get(id);
        var script = catalog.script(id).orElse(null);
        if (definition == null || script == null || !hooks.contains(hook) || !script.supportsHook(hook)
                || level < 1 || level > definition.maxLevel())
            return response(id, hookName, Status.INVALID, null, 0, 0);
        if (quarantined.contains(id)) return response(id, hookName, Status.QUARANTINED, null, 0, 0);
        if (!capabilities.containsAll(definition.requires())) return response(id, hookName, Status.UNAVAILABLE, null, 0, 0);

        var measured = LuaExecutionBudget.measure(() -> {
            var owner = new QueryOwner(id, level, definition.parametersAt(level), input, hooks, actor, target);
            ScriptInstance instance = new ScriptInstance(script, owner);
            try { return new Invocation(instance.handleQuery(hook, null,
                    target instanceof LivingEntity living ? living : null,
                    actor instanceof LivingEntity living ? living : null), owner.problem); }
            finally { instance.shutdown(); }
        }, nanos, instructions);
        ScriptQueryResult result = measured.value() == null ? null : measured.value().result();
        Status status;
        Double value = null;
        if (measured.exhausted()) status = Status.BUDGET_EXHAUSTED;
        else if (measured.failure() != null || result == null) status = Status.FAILED;
        else if (result.kind() == ScriptQueryResult.Kind.NIL) status = Status.OK;
        else if (result.kind() == ScriptQueryResult.Kind.NUMBER && Double.isFinite(result.number())) {
            status = Status.OK;
            value = result.number();
        } else status = Status.FAILED;
        if (status != Status.OK) {
            // A low remaining action allowance is not proof that this definition is broken.
            if (status != Status.BUDGET_EXHAUSTED) quarantined.add(id);
            String detail = measured.failure() != null ? measured.failure().getMessage()
                    : measured.value() == null ? "No query result" : measured.value().problem();
            if (detail == null) detail = "Expected nil or one finite numeric contribution";
            detail = detail.replaceAll("[\\r\\n\\p{Cntrl}]", " ");
            if (detail.length() > 512) detail = detail.substring(0, 512);
            if (warned.add(id)) warning.accept("Enchantment " + id + " hook " + hookName + " failed: " + status + ": " + detail);
        }
        return response(id, hookName, status, value, measured.chargedNanos(), measured.instructions());
    }

    private record Invocation(ScriptQueryResult result, String problem) { }

    private static Entity resolveEntity(String id) {
        return id.isEmpty() ? null : Bukkit.getEntity(UUID.fromString(id));
    }

    private static Map<String, Object> response(String id, String hook, Status status, Double value, long nanos, long instructions) {
        return Map.of("kind", EnchantmentQueries.CAPABILITY, "id", id, "hook", hook, "status", status.name(),
                "hasValue", value != null, "value", value == null ? 0D : value, "chargedNanos", nanos, "instructions", instructions);
    }

    private static final class QueryOwner extends ScriptableEntity {
        private final LuaTable enchantment;
        private final LuaTable parameters;
        private final LuaTable input;
        private final Set<ScriptHook> hooks;
        private final Entity actor;
        private final Entity target;
        private String problem;
        QueryOwner(String id, int level, Map<String, Object> parameters, Map<String, Object> input,
                   Set<ScriptHook> hooks, Entity actor, Entity target) {
            enchantment = table(Map.of("id", id, "level", level));
            this.parameters = table(parameters);
            this.input = table(input);
            this.hooks = hooks;
            this.actor = actor;
            this.target = target;
        }
        @Override public boolean handleScriptError(String context, Exception failure) {
            problem = failure.getMessage();
            return true;
        }
        @Override public LuaTable buildContextTable(ScriptInstance instance) { return enchantment; }
        @Override public String getContextKey() { return "enchantment"; }
        @Override public Set<ScriptHook> getSupportedHooks() { return hooks; }
        @Override public Entity getBukkitEntity() { return actor; }
        @Override public Location getLocation() { return actor == null ? null : actor.getLocation(); }
        @Override public LuaValue resolveExtraContext(String key, ScriptInstance instance) {
            return switch (key) {
                case "parameters" -> parameters;
                case "input" -> input;
                case "target" -> target instanceof LivingEntity living ? LuaLivingEntityTable.build(living)
                        : target == null ? LuaValue.NIL : LuaEntityTable.build(target);
                default -> LuaValue.NIL;
            };
        }
        private static LuaTable table(Map<String, Object> values) {
            LuaTable table = new LuaTable();
            values.forEach((key, value) -> table.set(key, value instanceof Boolean flag ? LuaValue.valueOf(flag)
                    : value instanceof Number number ? LuaValue.valueOf(number.doubleValue()) : LuaValue.valueOf((String) value)));
            return table;
        }
    }
}
