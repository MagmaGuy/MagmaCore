package com.magmaguy.magmacore.scripting;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Owns isolated module catalogs used to compose Lua-authored Mind programs.
 *
 * <p>Each owner scope controls one namespace. Module source is never exposed through Lua's
 * {@code require}; the registry resolves a declared dependency graph in Java, snapshots the
 * resolved sources into a {@link LuaMindDefinition}, and then evaluates that snapshot in a fresh
 * restricted Lua environment for every entity binding.</p>
 */
public final class LuaMindModuleRegistry implements AutoCloseable {
    public static final int MAX_MODULES_PER_OWNER = 256;
    public static final int MAX_DEPENDENCIES_PER_MODULE = 32;
    public static final int MAX_RESOLVED_MODULES_PER_PROGRAM = 64;
    public static final int MAX_SOURCE_CHARACTERS = 1_000_000;

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9._-]+");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");

    private final Map<String, OwnerScope> owners = new HashMap<>();
    private boolean closed;

    /** Opens the sole active module owner for a lower-case namespace. */
    public synchronized OwnerScope openOwner(String namespace) {
        requireOpen();
        requireNamespace(namespace);
        if (owners.containsKey(namespace)) {
            throw new IllegalStateException(
                    "Lua Mind module namespace already has an active owner: " + namespace);
        }
        OwnerScope owner = new OwnerScope(namespace);
        owners.put(namespace, owner);
        return owner;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (OwnerScope owner : List.copyOf(owners.values())) {
            owner.closeFromRegistry();
        }
        owners.clear();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Lua Mind module registry is closed");
    }

    private static void requireNamespace(String namespace) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "Lua Mind module owner namespace must be lower-case and path-safe");
        }
    }

    private static String namespaceOf(String identifier) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    "Lua Mind module identifiers must be namespaced lower-case keys: " + identifier);
        }
        int separator = identifier.indexOf(':');
        return identifier.substring(0, separator);
    }

    /** Owner-scoped registration and program-compilation interface. */
    public final class OwnerScope implements AutoCloseable {
        private final String namespace;
        private Map<String, LuaMindModuleSource> modules = new LinkedHashMap<>();
        private boolean ownerClosed;

        private OwnerScope(String namespace) {
            this.namespace = namespace;
        }

        public String namespace() {
            return namespace;
        }

        /**
         * Registers one {@code ai.module} source.
         *
         * <p>Repeating the same revision and source is idempotent. Changing source requires a
         * strictly newer revision. The candidate graph and all affected compositions validate
         * before this method replaces the current entry.</p>
         */
        public LuaMindModuleDescriptor registerModule(String sourceName, String source) {
            return registerModule(null, 0L, sourceName, source);
        }

        /**
         * Registers a module only when its Lua-declared identity matches the caller's catalog
         * identity. A mismatch leaves the owner catalog unchanged.
         */
        public LuaMindModuleDescriptor registerModule(
                String expectedIdentifier,
                long expectedRevision,
                String sourceName,
                String source) {
            synchronized (LuaMindModuleRegistry.this) {
                requireOwnerOpen();
                if (expectedIdentifier != null) {
                    requireOwnedIdentifier(expectedIdentifier);
                    if (expectedRevision < 1L) {
                        throw new IllegalArgumentException("Expected module revision must be positive");
                    }
                }
                requireSourceSize(sourceName, source);
                LuaMindModuleSource candidate = LuaMindModuleSource.inspect(sourceName, source);
                requireOwnedIdentifier(candidate.identifier());
                if (expectedIdentifier != null
                        && (!expectedIdentifier.equals(candidate.identifier())
                        || expectedRevision != candidate.revision())) {
                    throw new IllegalArgumentException(
                            "Lua Mind module identity does not match the caller catalog entry "
                                    + expectedIdentifier + "@" + expectedRevision);
                }
                if (candidate.dependencies().size() > MAX_DEPENDENCIES_PER_MODULE) {
                    throw new IllegalArgumentException(
                            "Lua Mind module " + candidate.identifier() + " exceeds the dependency limit of "
                                    + MAX_DEPENDENCIES_PER_MODULE);
                }
                for (String dependency : candidate.dependencies()) {
                    requireOwnedIdentifier(dependency);
                }

                LuaMindModuleSource current = modules.get(candidate.identifier());
                if (current != null) {
                    if (current.revision() == candidate.revision()
                            && current.source().equals(candidate.source())) {
                        return current.descriptor();
                    }
                    if (candidate.revision() <= current.revision()) {
                        throw new IllegalArgumentException(
                                "Lua Mind module replacements require a newer revision: "
                                        + candidate.identifier());
                    }
                }

                Map<String, LuaMindModuleSource> proposed = new LinkedHashMap<>(modules);
                proposed.put(candidate.identifier(), candidate);
                if (proposed.size() > MAX_MODULES_PER_OWNER) {
                    throw new IllegalArgumentException(
                            "Lua Mind module owner exceeds the module limit of "
                                    + MAX_MODULES_PER_OWNER);
                }
                validateCatalog(proposed);
                modules = proposed;
                return candidate.descriptor();
            }
        }

        /** Validates and snapshots a module-composed {@code ai.program}. */
        public LuaMindDefinition validateProgram(String sourceName, String source) {
            synchronized (LuaMindModuleRegistry.this) {
                requireOwnerOpen();
                requireSourceSize(sourceName, source);
                LuaMindProgramMetadata metadata = LuaMindSourceReader.inspectProgram(
                        sourceName,
                        source);
                requireOwnedIdentifier(metadata.identifier());
                for (String module : metadata.modules()) {
                    requireOwnedIdentifier(module);
                }
                List<LuaMindModuleSource> resolved = resolve(
                        modules,
                        metadata.modules(),
                        "Mind program " + metadata.identifier());
                return LuaMindDefinition.validateResolved(
                        sourceName,
                        source,
                        metadata,
                        resolved);
            }
        }

        public Optional<LuaMindModuleDescriptor> module(String identifier) {
            synchronized (LuaMindModuleRegistry.this) {
                requireOwnerOpen();
                LuaMindModuleSource module = modules.get(identifier);
                return module == null ? Optional.empty() : Optional.of(module.descriptor());
            }
        }

        /** Returns descriptors ordered by module identifier. */
        public List<LuaMindModuleDescriptor> modules() {
            synchronized (LuaMindModuleRegistry.this) {
                requireOwnerOpen();
                return modules.values().stream()
                        .sorted(Comparator.comparing(LuaMindModuleSource::identifier))
                        .map(LuaMindModuleSource::descriptor)
                        .toList();
            }
        }

        @Override
        public void close() {
            synchronized (LuaMindModuleRegistry.this) {
                if (ownerClosed) return;
                ownerClosed = true;
                modules = Map.of();
                owners.remove(namespace, this);
            }
        }

        private void closeFromRegistry() {
            ownerClosed = true;
            modules = Map.of();
        }

        private void requireOwnerOpen() {
            requireOpen();
            if (ownerClosed) {
                throw new IllegalStateException(
                        "Lua Mind module owner is closed: " + namespace);
            }
        }

        private void requireOwnedIdentifier(String identifier) {
            if (!namespace.equals(namespaceOf(identifier))) {
                throw new IllegalArgumentException(
                        "Lua Mind identifier " + identifier
                                + " does not belong to owner namespace " + namespace);
            }
        }

        private void validateCatalog(Map<String, LuaMindModuleSource> proposed) {
            List<String> roots = proposed.keySet().stream().sorted().toList();
            for (String root : roots) {
                List<LuaMindModuleSource> composition = resolve(
                        proposed,
                        List.of(root),
                        "Mind module " + root);
                validateComposition(namespace, root, composition);
            }
        }

        private void requireSourceSize(String sourceName, String source) {
            Objects.requireNonNull(sourceName, "sourceName");
            Objects.requireNonNull(source, "source");
            if (source.length() > MAX_SOURCE_CHARACTERS) {
                throw new IllegalArgumentException(
                        "Lua Mind source " + sourceName + " exceeds the character limit of "
                                + MAX_SOURCE_CHARACTERS);
            }
        }
    }

    private static List<LuaMindModuleSource> resolve(
            Map<String, LuaMindModuleSource> modules,
            List<String> roots,
            String consumer) {
        Map<String, Visit> visits = new HashMap<>();
        List<String> path = new ArrayList<>();
        LinkedHashMap<String, LuaMindModuleSource> resolved = new LinkedHashMap<>();
        for (String root : roots) {
            resolve(root, modules, visits, path, resolved, consumer);
        }
        return List.copyOf(resolved.values());
    }

    private static void resolve(
            String identifier,
            Map<String, LuaMindModuleSource> modules,
            Map<String, Visit> visits,
            List<String> path,
            LinkedHashMap<String, LuaMindModuleSource> resolved,
            String consumer) {
        Visit visit = visits.get(identifier);
        if (visit == Visit.COMPLETE) return;
        if (visit == Visit.ACTIVE) {
            int cycleStart = path.indexOf(identifier);
            List<String> cycle = new ArrayList<>(path.subList(cycleStart, path.size()));
            cycle.add(identifier);
            throw new IllegalArgumentException(
                    consumer + " contains a module dependency cycle: "
                            + String.join(" -> ", cycle));
        }

        LuaMindModuleSource module = modules.get(identifier);
        if (module == null) {
            throw new IllegalArgumentException(
                    consumer + " requires missing Lua Mind module " + identifier);
        }
        visits.put(identifier, Visit.ACTIVE);
        path.add(identifier);
        for (String dependency : module.dependencies()) {
            resolve(dependency, modules, visits, path, resolved, consumer);
        }
        path.removeLast();
        visits.put(identifier, Visit.COMPLETE);
        resolved.put(identifier, module);
        if (resolved.size() > MAX_RESOLVED_MODULES_PER_PROGRAM) {
            throw new IllegalArgumentException(
                    consumer + " exceeds the resolved module limit of "
                            + MAX_RESOLVED_MODULES_PER_PROGRAM);
        }
    }

    private static void validateComposition(
            String namespace,
            String root,
            List<LuaMindModuleSource> modules) {
        LuaExecutionBudget.run(() -> {
            Globals globals = LuaEnvironmentFactory.createGlobals();
            List<LuaTable> tables = new ArrayList<>(modules.size());
            for (LuaMindModuleSource module : modules) {
                tables.add(module.evaluate(globals));
            }
            LuaTable syntheticProgram = new LuaTable();
            syntheticProgram.set("__mind_kind", "program");
            syntheticProgram.set("id", namespace + ":module_validation/"
                    + Integer.toUnsignedString(root.hashCode(), 36));
            syntheticProgram.set("revision", 1L);
            LuaMindProgramCompiler.compile(syntheticProgram, tables);
            return null;
        });
    }

    private enum Visit {
        ACTIVE,
        COMPLETE
    }
}
