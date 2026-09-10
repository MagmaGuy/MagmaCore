package com.magmaguy.magmacore.enchantments;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Provider discovery and calls across relocated MagmaCore copies. All operations run on the
 * server thread. Only JDK/Bukkit values cross the service boundary; local records stay local.
 * This module does not install enchantments, dispatch combat events or apply damage.
 */
public final class EnchantmentProviders {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9._-]{1,64}");
    private static final Pattern CAPABILITY = Pattern.compile("[a-z0-9._:-]{1,128}");

    private EnchantmentProviders() { }

    public enum Operation { RESOLVE, VALIDATE, EVALUATE }
    public enum Status { OK, UNAVAILABLE, INCOMPATIBLE, CONFLICT, STALE, UNSUPPORTED, INVALID_REQUEST, WRONG_THREAD, FAILED }

    @FunctionalInterface
    public interface Handler {
        Map<String, Object> handle(Operation operation, Map<String, Object> payload);
    }

    public record Provider(String namespace, String plugin, UUID generation, long revision,
                           int protocolVersion, Set<String> capabilities) {
        public Provider { capabilities = Set.copyOf(capabilities); }
        public boolean compatible() { return protocolVersion == EnchantmentProviderEndpoint.VERSION; }
    }

    public record Result(Status status, Map<String, Object> payload) {
        public Result { payload = EnchantmentValues.copy(payload); }
        @Override public Map<String, Object> payload() { return EnchantmentValues.copy(payload); }
    }

    /** Ownership token. Closing it revokes even a previously captured endpoint reference. */
    public static final class Registration implements AutoCloseable {
        private final EnchantmentProviderEndpoint endpoint;
        private boolean closed;
        private Registration(EnchantmentProviderEndpoint endpoint) { this.endpoint = endpoint; }
        public long advanceRevision() { return endpoint.advanceRevision(); }
        @Override public void close() {
            requireServerThread();
            if (closed) return;
            closed = true;
            endpoint.deactivate();
            Bukkit.getServicesManager().unregister(BiFunction.class, endpoint);
        }
    }

    public static Registration register(Plugin owner, String namespace, Set<String> capabilities, Handler handler) {
        requireServerThread();
        Objects.requireNonNull(owner, "owner");
        if (!owner.isEnabled()) throw new IllegalStateException("Provider plugin is disabled");
        validateNamespace(namespace);
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.size() > 128 || capabilities.stream().anyMatch(c -> c == null || !CAPABILITY.matcher(c).matches()))
            throw new IllegalArgumentException("Invalid capabilities");
        Objects.requireNonNull(handler, "handler");
        if (entries().stream().anyMatch(e -> e.provider.namespace().equals(namespace)))
            throw new IllegalStateException("Enchantment namespace already owned: " + namespace);
        var endpoint = new EnchantmentProviderEndpoint(owner, namespace, capabilities, handler);
        Bukkit.getServicesManager().register(BiFunction.class, endpoint, owner, ServicePriority.Normal);
        return new Registration(endpoint);
    }

    /** Returns independent descriptors, including incompatible peers for operator diagnosis. */
    public static List<Provider> providers() {
        requireServerThread();
        return entries().stream().map(Entry::provider)
                .sorted(java.util.Comparator.comparing(Provider::namespace).thenComparing(Provider::plugin)).toList();
    }

    /** A conflict has no winner, regardless of Bukkit service priority or registration order. */
    public static Optional<Provider> find(String namespace) {
        requireServerThread();
        validateNamespace(namespace);
        List<Entry> matches = matching(namespace);
        if (matches.size() > 1) throw new IllegalStateException("Conflicting enchantment namespace: " + namespace);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.getFirst().provider);
    }

    /** Pins a request to the descriptor observed by the caller; reload never silently retargets it. */
    public static Result call(Provider expected, Operation operation, Map<String, Object> payload) {
        requireServerThread();
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(operation, "operation");
        List<Entry> matches = matching(expected.namespace());
        if (matches.isEmpty()) return result(Status.UNAVAILABLE);
        if (matches.size() > 1) return result(Status.CONFLICT);
        Entry entry = matches.getFirst();
        if (!expected.compatible() || !entry.provider.compatible()) return result(Status.INCOMPATIBLE);
        if (!entry.provider.equals(expected)) return result(Status.STALE);
        Map<String, Object> copied;
        try { copied = EnchantmentValues.copy(payload); }
        catch (IllegalArgumentException invalid) { return result(Status.INVALID_REQUEST); }
        try {
            Map<String, Object> response = entry.endpoint.apply(operation.name(), Map.of(
                    "protocol", EnchantmentProviderEndpoint.PROTOCOL, "version", EnchantmentProviderEndpoint.VERSION,
                    "generation", expected.generation(), "revision", expected.revision(), "payload", copied));
            if (response == null || !(response.get("status") instanceof String statusText)) return result(Status.FAILED);
            Status status = Status.valueOf(statusText);
            if (status != Status.OK) return result(status);
            if (!response.keySet().equals(Set.of("status", "payload")) || !(response.get("payload") instanceof Map<?, ?> output))
                return result(Status.FAILED);
            // A peer can unregister through Bukkit without explicitly closing its token.
            List<Entry> current = matching(expected.namespace());
            if (current.size() > 1) return result(Status.CONFLICT);
            if (current.isEmpty() || current.getFirst().endpoint != entry.endpoint || !current.getFirst().provider.equals(expected))
                return result(Status.STALE);
            return new Result(Status.OK, EnchantmentValues.copy(output));
        } catch (RuntimeException | LinkageError failure) {
            return result(Status.FAILED);
        }
    }

    private static Result result(Status status) { return new Result(status, Map.of()); }

    static void requireServerThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Enchantment providers require the server thread");
    }

    private static void validateNamespace(String namespace) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches())
            throw new IllegalArgumentException("Namespace must be a lowercase identifier");
    }

    private static List<Entry> matching(String namespace) {
        return entries().stream().filter(e -> e.provider.namespace().equals(namespace)).toList();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        for (RegisteredServiceProvider<BiFunction> service : Bukkit.getServicesManager().getRegistrations(BiFunction.class)) {
            Object candidate = service.getProvider();
            if (!service.getPlugin().isEnabled() || !candidate.getClass().getSimpleName().equals("EnchantmentProviderEndpoint")) continue;
            try {
                BiFunction<String, Map<String, Object>, Map<String, Object>> endpoint = (BiFunction) candidate;
                Object raw = endpoint.apply("describe", Map.of());
                if (!(raw instanceof Map<?, ?> data) || !"OK".equals(data.get("status"))
                        || !EnchantmentProviderEndpoint.PROTOCOL.equals(data.get("protocol"))) continue;
                if (!(data.get("namespace") instanceof String namespace)
                        || !(data.get("plugin") instanceof String plugin) || !plugin.equals(service.getPlugin().getName())
                        || !(data.get("generation") instanceof UUID generation)
                        || !(data.get("revision") instanceof Long revision) || revision < 1
                        || !(data.get("version") instanceof Integer version) || version < 1
                        || !(data.get("capabilities") instanceof List<?> capabilities) || capabilities.size() > 128) continue;
                validateNamespace(namespace);
                Set<String> verified = new HashSet<>();
                for (Object capability : capabilities) {
                    if (!(capability instanceof String name) || !CAPABILITY.matcher(name).matches() || !verified.add(name))
                        throw new IllegalArgumentException("Malformed capabilities");
                }
                result.add(new Entry(new Provider(namespace, plugin, generation, revision, version, verified), endpoint));
            } catch (RuntimeException | LinkageError malformed) {
                // Ignore unrelated/malformed registrations. They cannot become executable peers.
            }
        }
        return result;
    }

    private record Entry(Provider provider, BiFunction<String, Map<String, Object>, Map<String, Object>> endpoint) { }
}
