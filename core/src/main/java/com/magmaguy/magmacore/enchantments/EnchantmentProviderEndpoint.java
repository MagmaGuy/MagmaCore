package com.magmaguy.magmacore.enchantments;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.logging.Level;

/** Service marker. Its simple name and JDK method signature survive shading. */
public final class EnchantmentProviderEndpoint implements BiFunction<String, Map<String, Object>, Map<String, Object>>, Listener {
    static final String PROTOCOL = "magmacore.enchantments";
    static final int VERSION = 1;
    private final Plugin owner;
    private final String namespace;
    private final Set<String> capabilities;
    private final EnchantmentProviders.Handler handler;
    private final UUID generation = UUID.randomUUID();
    private long revision = 1;
    private boolean active = true;
    private boolean failureWarned;
    private Map<String, Object> descriptor;

    EnchantmentProviderEndpoint(Plugin owner, String namespace, Set<String> capabilities,
                                EnchantmentProviders.Handler handler) {
        this.owner = owner;
        this.namespace = namespace;
        this.capabilities = Set.copyOf(capabilities);
        this.handler = handler;
        refreshDescriptor();
    }

    @Override
    public Map<String, Object> apply(String operation, Map<String, Object> request) {
        if (!Bukkit.isPrimaryThread()) return failure("WRONG_THREAD");
        if (!available()) return failure("UNAVAILABLE");
        if ("describe".equals(operation)) {
            return descriptor;
        }
        try {
            if (request == null || !request.keySet().equals(Set.of("protocol", "version", "generation", "revision", "payload")))
                return failure("INVALID_REQUEST");
            if (!PROTOCOL.equals(request.get("protocol")) || !Integer.valueOf(VERSION).equals(request.get("version")))
                return failure("INCOMPATIBLE");
            if (!generation.equals(request.get("generation")) || !Long.valueOf(revision).equals(request.get("revision")))
                return failure("STALE");
            EnchantmentProviders.Operation kind;
            try { kind = EnchantmentProviders.Operation.valueOf(operation); }
            catch (IllegalArgumentException | NullPointerException invalid) { return failure("UNSUPPORTED"); }
            if (!(request.get("payload") instanceof Map<?, ?> payload)) return failure("INVALID_REQUEST");
            Map<String, Object> input;
            try { input = EnchantmentValues.copy(payload); }
            catch (IllegalArgumentException invalid) { return failure("INVALID_REQUEST"); }
            Map<String, Object> output = EnchantmentValues.copy(handler.handle(kind, input));
            // A callback may disable/reload its own provider. Never return a stale success.
            if (!available()) return failure("UNAVAILABLE");
            if (!Long.valueOf(revision).equals(request.get("revision"))) return failure("STALE");
            return Map.of("status", "OK", "payload", output);
        } catch (RuntimeException | LinkageError failure) {
            if (!failureWarned) {
                failureWarned = true;
                owner.getLogger().log(Level.WARNING, "Enchantment provider " + namespace + " failed " + operation
                        + "; further failures suppressed until its revision changes", failure);
            }
            return failure("FAILED");
        }
    }

    long advanceRevision() {
        EnchantmentProviders.requireServerThread();
        if (!available()) throw new IllegalStateException("Provider is unavailable");
        revision = Math.incrementExact(revision);
        failureWarned = false;
        refreshDescriptor();
        return revision;
    }

    private void refreshDescriptor() {
        descriptor = Map.of("status", "OK", "protocol", PROTOCOL, "version", VERSION,
                "namespace", namespace, "plugin", owner.getName(), "generation", generation,
                "revision", revision, "capabilities", capabilities.stream().sorted().toList());
    }

    @EventHandler
    public void onUnregister(ServiceUnregisterEvent event) {
        if (event.getProvider().getProvider() == this) deactivate();
    }

    void deactivate() {
        active = false;
        HandlerList.unregisterAll(this);
    }

    private boolean available() {
        return active && owner.isEnabled();
    }

    static Map<String, Object> failure(String status) { return Map.of("status", status); }
}
