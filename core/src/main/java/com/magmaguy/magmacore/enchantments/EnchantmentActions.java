package com.magmaguy.magmacore.enchantments;

import com.magmaguy.magmacore.scripting.ScriptHook;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Provider-owned Lua effects. Only copied facts and stable identities cross the shaded bridge. */
public final class EnchantmentActions {
    public static final String CAPABILITY = "enchantment.actions.v1";
    private EnchantmentActions() { }

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
