package com.magmaguy.magmacore.enchantments;

import com.magmaguy.magmacore.scripting.ScriptHook;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Provider-owned Lua effects. Only copied facts and stable identities cross the shaded bridge. */
public final class EnchantmentActions {
    /** Provider-local view of copied action facts; only Bukkit/JDK values travel between owners. */
    public record DamageInput(UUID attackId, org.bukkit.entity.Player actor,
                              org.bukkit.entity.LivingEntity target, double amount,
                              Map<String, org.bukkit.inventory.ItemStack> equipment,
                              Map<String, Object> providerFacts) {
        /** Called inside the provider's combat attribution scope, including native projectile impacts. */
        public void applyDamage() {
            com.magmaguy.magmacore.scripting.ScriptNativeProjectiles.applyAttributedDamage(target, amount, actor);
        }

        public static DamageInput read(Map<String, Object> request) {
            EnchantmentProviders.requireServerThread();
            if (!request.keySet().equals(java.util.Set.of("kind", "attack", "actor", "world", "target", "amount", "facts"))
                    || !"attributed_damage".equals(request.get("kind"))
                    || !(request.get("attack") instanceof UUID attack) || !(request.get("actor") instanceof UUID actorId)
                    || !(request.get("world") instanceof UUID world) || !(request.get("target") instanceof UUID targetId)
                    || !(request.get("amount") instanceof Double amount) || !Double.isFinite(amount) || amount <= 0
                    || !(request.get("facts") instanceof Map<?, ?> facts))
                throw new IllegalArgumentException("Invalid attributed damage request");
            var actorEntity = org.bukkit.Bukkit.getEntity(actorId);
            var targetEntity = org.bukkit.Bukkit.getEntity(targetId);
            if (!(actorEntity instanceof org.bukkit.entity.Player actor) || !actor.isOnline() || !actor.isValid()
                    || actor.isDead() || !(targetEntity instanceof org.bukkit.entity.LivingEntity target)
                    || !target.isValid() || target.isDead() || !actor.getWorld().getUID().equals(world)
                    || !target.getWorld().getUID().equals(world)) return null;
            Map<String, org.bukkit.inventory.ItemStack> equipment = new java.util.LinkedHashMap<>();
            if (facts.get("equipment") instanceof Map<?, ?> captured)
                for (var entry : captured.entrySet()) {
                    if (!(entry.getKey() instanceof String slot)
                            || !(entry.getValue() instanceof org.bukkit.inventory.ItemStack item))
                        throw new IllegalArgumentException("Invalid captured equipment");
                    EnchantmentDefinition.Slot.valueOf(slot);
                    equipment.put(slot, item.clone());
                }
            Map<String, Object> providerFacts = facts.get("providers") instanceof Map<?, ?> values
                    ? EnchantmentValues.copy(values) : Map.of();
            return new DamageInput(attack, actor, target, amount, Map.copyOf(equipment), providerFacts);
        }
    }
    public static final String CAPABILITY = "enchantment.actions.v1";
    public static final String SOURCE_FACTS = "enchantment.source_facts.v1";
    private EnchantmentActions() { }

    /** Provider combat facts are evaluated at input/launch, never again at delayed impact. */
    public static Map<String, Object> captureProviderFacts(UUID actor, Map<String, org.bukkit.inventory.ItemStack> equipment) {
        EnchantmentProviders.requireServerThread();
        Map<String, Object> facts = new java.util.LinkedHashMap<>();
        for (var provider : EnchantmentProviders.providers()) {
            if (!provider.compatible() || !provider.capabilities().contains(SOURCE_FACTS)) continue;
            var result = EnchantmentProviders.call(provider, EnchantmentProviders.Operation.EVALUATE,
                    Map.of("kind", SOURCE_FACTS, "actor", actor, "equipment", equipment));
            if (result.status() != EnchantmentProviders.Status.OK)
                throw new IllegalArgumentException("Cannot capture source facts for " + provider.namespace());
            facts.put(provider.namespace(), result.payload());
        }
        return EnchantmentValues.copy(facts);
    }

    /** Six equipped slots at acceptance/launch. Values are detached from the live inventory. */
    public static Map<String, org.bukkit.inventory.ItemStack> captureEquipment(org.bukkit.entity.LivingEntity actor) {
        EnchantmentProviders.requireServerThread();
        var equipment = Objects.requireNonNull(actor.getEquipment(), "actor equipment");
        Map<String, org.bukkit.inventory.ItemStack> captured = new java.util.LinkedHashMap<>();
        for (var slot : EnchantmentDefinition.Slot.values()) {
            org.bukkit.inventory.ItemStack item = switch (slot) {
                case MAINHAND -> equipment.getItemInMainHand();
                case OFFHAND -> equipment.getItemInOffHand();
                case HEAD -> equipment.getHelmet();
                case CHEST -> equipment.getChestplate();
                case LEGS -> equipment.getLeggings();
                case FEET -> equipment.getBoots();
            };
            if (item != null && !item.getType().isAir() && item.getAmount() > 0)
                captured.put(slot.name(), item.clone());
        }
        return Map.copyOf(captured);
    }

    /** The host chooses the lifetime from the authored effect, with no global gameplay quota. */
    public record Source(UUID attackId, UUID actor, UUID world, long lifetimeTicks, Map<String, Object> facts) {
        public Source {
            Objects.requireNonNull(attackId, "attackId");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(world, "world");
            if (lifetimeTicks < 1) throw new IllegalArgumentException("An action needs a positive lifetime");
            facts = EnchantmentValues.copy(facts);
        }
        @Override public Map<String, Object> facts() { return EnchantmentValues.copy(facts); }
    }

    public enum Status { OK, DUPLICATE, UNAVAILABLE, STALE, INVALID, FAILED }

    /** One accepted activation. Keep this handle for every subsequent impact/stage of that effect. */
    public static final class Action implements AutoCloseable {
        private final EnchantmentProviders.Provider provider;
        private final String token;
        private boolean closed;
        private boolean cancelledInput;

        private Action(EnchantmentProviders.Provider provider, String token) {
            this.provider = provider;
            this.token = token;
        }

        /** Stage identity includes the projectile/impact identity; duplicate delivery cannot replay Lua. */
        public Status dispatch(String stage, ScriptHook hook, UUID target) {
            return dispatch(stage, hook, target, false);
        }

        /** Last input for this activation. Owned callbacks/restoration continue; an idle effect closes immediately. */
        public Status dispatchFinal(String stage, ScriptHook hook, UUID target) {
            return dispatch(stage, hook, target, true);
        }

        /** Cancellation requested synchronously by the most recently dispatched input. */
        public boolean cancelledInput() { return cancelledInput; }

        private Status dispatch(String stage, ScriptHook hook, UUID target, boolean finalStage) {
            EnchantmentProviders.requireServerThread();
            cancelledInput = false;
            if (closed) return Status.UNAVAILABLE;
            Objects.requireNonNull(hook, "hook");
            if (stage == null || stage.isBlank()) throw new IllegalArgumentException("Missing stage identity");
            var result = EnchantmentProviders.call(provider, EnchantmentProviders.Operation.EVALUATE,
                    Map.of("kind", CAPABILITY, "operation", "dispatch", "token", token,
                            "stage", stage, "hook", hook.getKey(), "target", target == null ? "" : target.toString(), "final", finalStage));
            cancelledInput = Boolean.TRUE.equals(result.payload().get("cancelled"));
            return status(result);
        }

        @Override public void close() {
            EnchantmentProviders.requireServerThread();
            if (closed) return;
            closed = true;
            EnchantmentProviders.call(provider, EnchantmentProviders.Operation.EVALUATE,
                    Map.of("kind", CAPABILITY, "operation", "close", "token", token));
        }
    }

    /** Called once by the host after accepting the action, using its launch-time definition/revision. */
    public static Action begin(EnchantmentItems.Resolved resolved, int level, Source source) {
        return begin(resolved, level, source, null);
    }

    /** The input owner can avoid creating a timed instance when this definition has no matching hook. */
    public static Action begin(EnchantmentItems.Resolved resolved, int level, Source source, ScriptHook initialHook) {
        EnchantmentProviders.requireServerThread();
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(source, "source");
        if (!resolved.provider().capabilities().contains(CAPABILITY)) return null;
        var result = EnchantmentProviders.call(resolved.provider(), EnchantmentProviders.Operation.EVALUATE,
                Map.of("kind", CAPABILITY, "operation", "begin", "id", resolved.definition().id(),
                        "level", level, "attack", source.attackId().toString(), "actor", source.actor().toString(),
                        "world", source.world().toString(), "lifetime", source.lifetimeTicks(), "facts", source.facts(),
                        "hook", initialHook == null ? "" : initialHook.getKey()));
        if (status(result) != Status.OK || !(result.payload().get("token") instanceof String token)) return null;
        UUID.fromString(token);
        return new Action(resolved.provider(), token);
    }

    private static Status status(EnchantmentProviders.Result result) {
        if (result.status() != EnchantmentProviders.Status.OK) return switch (result.status()) {
            case STALE -> Status.STALE;
            case INVALID_REQUEST -> Status.INVALID;
            case FAILED -> Status.FAILED;
            default -> Status.UNAVAILABLE;
        };
        try { return Status.valueOf((String) result.payload().get("status")); }
        catch (RuntimeException malformed) { return Status.FAILED; }
    }
}
