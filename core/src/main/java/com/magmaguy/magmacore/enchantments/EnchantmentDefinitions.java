package com.magmaguy.magmacore.enchantments;

import org.bukkit.plugin.Plugin;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Publishes provider-owned catalogs through the existing cross-shading bridge. No Lua state crosses it. */
public final class EnchantmentDefinitions {
    private EnchantmentDefinitions() { }

    public static HostedCatalog publish(Plugin owner, EnchantmentCatalog catalog, Set<String> capabilities,
                                       EnchantmentProviders.Handler domainHandler) {
        EnchantmentProviders.requireServerThread();
        return new HostedCatalog(owner, catalog, capabilities, domainHandler);
    }

    public static final class HostedCatalog implements AutoCloseable {
        private EnchantmentCatalog catalog;
        private final EnchantmentProviders.Registration registration;
        private boolean closed;

        private HostedCatalog(Plugin owner, EnchantmentCatalog initial, Set<String> capabilities,
                              EnchantmentProviders.Handler domainHandler) {
            catalog = Objects.requireNonNull(initial, "catalog");
            Objects.requireNonNull(domainHandler, "domainHandler");
            registration = EnchantmentProviders.register(owner, initial.namespace(), capabilities, (operation, request) -> {
                if (operation != EnchantmentProviders.Operation.RESOLVE) return domainHandler.handle(operation, request);
                if (!request.keySet().equals(Set.of("id")) || !(request.get("id") instanceof String id))
                    throw new IllegalArgumentException("Expected one enchantment id");
                EnchantmentDefinition.requireId(id);
                if (!id.startsWith(catalog.namespace() + ":")) throw new IllegalArgumentException("Foreign enchantment namespace");
                EnchantmentDefinition definition = catalog.definitions().get(id);
                return definition == null ? Map.of("found", false) : Map.of("found", true, "definition", encode(definition));
            });
        }

        /** Accept only a fully validated candidate. A failed load never reaches this publication step. */
        public void reload(EnchantmentCatalog candidate) {
            EnchantmentProviders.requireServerThread();
            Objects.requireNonNull(candidate, "candidate");
            if (closed) throw new IllegalStateException("Enchantment catalog is closed");
            if (!catalog.namespace().equals(candidate.namespace())) throw new IllegalArgumentException("Cannot change catalog ownership");
            registration.advanceRevision();
            catalog = candidate;
        }

        public EnchantmentCatalog catalog() { EnchantmentProviders.requireServerThread(); return catalog; }

        @Override public void close() {
            EnchantmentProviders.requireServerThread();
            registration.close();
            closed = true;
        }
    }

    /** Item operations use this local typed resolver; peer definitions contain JDK values only. */
    public static EnchantmentItems.Resolved resolve(String id) {
        EnchantmentProviders.requireServerThread();
        EnchantmentDefinition.requireId(id);
        String namespace = id.substring(0, id.indexOf(':'));
        if (namespace.equals("minecraft")) throw new IllegalArgumentException("Native enchantments use the native registry");
        var provider = EnchantmentProviders.find(namespace).orElse(null);
        if (provider == null || !provider.compatible()) return null;
        var response = EnchantmentProviders.call(provider, EnchantmentProviders.Operation.RESOLVE, Map.of("id", id));
        if (response.status() != EnchantmentProviders.Status.OK) return null;
        Map<String, Object> payload = response.payload();
        if (payload.equals(Map.of("found", false))) return null;
        if (!payload.keySet().equals(Set.of("found", "definition")) || !Boolean.TRUE.equals(payload.get("found"))
                || !(payload.get("definition") instanceof Map<?, ?> raw))
            throw new IllegalArgumentException("Malformed enchantment definition response from " + namespace);
        EnchantmentDefinition definition = decode(raw);
        if (!definition.id().equals(id)) throw new IllegalArgumentException("Provider returned a different enchantment identity");
        return new EnchantmentItems.Resolved(definition, provider);
    }

    private static Map<String, Object> encode(EnchantmentDefinition definition) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1); data.put("id", definition.id()); data.put("name", definition.name());
        data.put("description", definition.description()); data.put("maxLevel", definition.maxLevel());
        data.put("curse", definition.curse()); data.put("validSlots", names(definition.validSlots()));
        data.put("itemTypes", names(definition.itemTypes())); data.put("attackKinds", definition.attackKinds().stream().sorted().toList());
        data.put("requires", definition.requires().stream().sorted().toList()); data.put("conflicts", definition.conflicts().stream().sorted().toList());
        data.put("stacking", definition.stacking().name()); data.put("script", definition.script()); data.put("parameters", definition.parameters());
        return Map.copyOf(data);
    }

    private static EnchantmentDefinition decode(Map<?, ?> data) {
        if (!data.keySet().equals(Set.of("version", "id", "name", "description", "maxLevel", "curse", "validSlots",
                "itemTypes", "attackKinds", "requires", "conflicts", "stacking", "script", "parameters"))
                || !Integer.valueOf(1).equals(data.get("version"))) throw new IllegalArgumentException("Unknown definition record");
        if (!(data.get("maxLevel") instanceof Integer maximum) || !(data.get("curse") instanceof Boolean curse)
                || !(data.get("parameters") instanceof Map<?, ?> parameters)) throw new IllegalArgumentException("Invalid definition field types");
        return new EnchantmentDefinition(string(data, "id"), string(data, "name"), string(data, "description"), maximum, curse,
                strings(data, "validSlots", EnchantmentDefinition.Slot::valueOf),
                strings(data, "itemTypes", EnchantmentDefinition.ItemType::valueOf), strings(data, "attackKinds", Function.identity()),
                strings(data, "requires", Function.identity()), strings(data, "conflicts", Function.identity()),
                EnchantmentDefinition.Stacking.valueOf(string(data, "stacking")), string(data, "script"), EnchantmentValues.copy(parameters));
    }

    private static List<String> names(Set<? extends Enum<?>> values) { return values.stream().map(Enum::name).sorted().toList(); }
    private static String string(Map<?, ?> data, String key) {
        if (!(data.get(key) instanceof String value)) throw new IllegalArgumentException("Expected string: " + key);
        return value;
    }
    private static <T> Set<T> strings(Map<?, ?> data, String key, Function<String, T> parser) {
        if (!(data.get(key) instanceof List<?> values) || values.size() > 128) throw new IllegalArgumentException("Expected bounded list: " + key);
        Set<T> parsed = new LinkedHashSet<>();
        for (Object value : values)
            if (!(value instanceof String text) || !parsed.add(parser.apply(text))) throw new IllegalArgumentException("Invalid or repeated entry: " + key);
        return Set.copyOf(parsed);
    }
}
