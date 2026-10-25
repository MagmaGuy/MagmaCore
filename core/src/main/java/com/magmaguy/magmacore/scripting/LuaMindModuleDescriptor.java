package com.magmaguy.magmacore.scripting;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Immutable identity and dependency metadata for one registered Lua Mind module. {@code settings} holds the defaults
 * a module declares in its {@code settings} table: Double, Boolean, String or List of String values.
 */
public record LuaMindModuleDescriptor(
        String identifier,
        long revision,
        List<String> dependencies,
        Map<String, Object> settings) {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");

    public LuaMindModuleDescriptor {
        Objects.requireNonNull(identifier, "identifier");
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    "Lua Mind module identifiers must be namespaced lower-case keys");
        }
        if (revision < 1L) throw new IllegalArgumentException("revision must be positive");
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        if (new HashSet<>(dependencies).size() != dependencies.size()) {
            throw new IllegalArgumentException("Lua Mind module dependencies cannot repeat");
        }
        for (String dependency : dependencies) {
            if (dependency == null || !IDENTIFIER.matcher(dependency).matches()) {
                throw new IllegalArgumentException(
                        "Lua Mind module dependencies must be namespaced lower-case keys");
            }
        }
        settings = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(settings, "settings")));
    }

    public LuaMindModuleDescriptor(String identifier, long revision, List<String> dependencies) {
        this(identifier, revision, dependencies, Map.of());
    }
}
