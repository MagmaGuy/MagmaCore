package com.magmaguy.magmacore.config;

import com.magmaguy.magmacore.scripting.ScriptDefinition;
import com.magmaguy.magmacore.scripting.ScriptHook;
import com.magmaguy.magmacore.scripting.ScriptProvider;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.IdentityHashMap;

/** Preserves whole retired configuration files using the owning plugin's explicit format rules. */
public final class OutdatedConfigurationArchive {
    public static final String RULES_RESOURCE = "outdated-config-keys.yml";
    public static final String DIRECTORY = "outdated files";
    private static final int MAX_SOURCE_BYTES = 4 * 1024 * 1024;
    private static final Map<JavaPlugin, OutdatedConfigurationArchiveOwner> owners = new IdentityHashMap<>();

    private OutdatedConfigurationArchive() { }

    /** Register from the owner's MagmaCore during onLoad, before any importer can start. */
    public static void register(JavaPlugin plugin) {
        if (owners.containsKey(plugin)) return;
        var owner = new OutdatedConfigurationArchiveOwner(plugin);
        Bukkit.getServicesManager().register(Runnable.class, owner, plugin, ServicePriority.Normal);
        owners.put(plugin, owner);
    }

    public static void unregister(JavaPlugin plugin) {
        var owner = owners.remove(plugin);
        if (owner == null) return;
        owner.active = false;
        Bukkit.getServicesManager().unregister(Runnable.class, owner);
    }

    /**
     * Request archival through the target's shaded implementation. Only a JDK Runnable crosses
     * class loaders; the target's YAML, rule types and parser never leave their owning copy.
     * Runs synchronously on the caller's import worker, preserving pre/post-import ordering.
     */
    public static void archiveFor(JavaPlugin plugin) {
        Runnable selected = null;
        for (var service : Bukkit.getServicesManager().getRegistrations(Runnable.class)) {
            if (service.getPlugin() != plugin || !service.getProvider().getClass().getSimpleName()
                    .equals("OutdatedConfigurationArchiveOwner")) continue;
            if (selected != null)
                throw new IllegalStateException("Multiple configuration archive owners for " + plugin.getName());
            selected = service.getProvider();
        }
        if (selected != null) {
            selected.run();
            return;
        }
        // Presence is safe to check across versions; interpreting another plugin's policy is not.
        // Never silently overwrite retired files when an older target lacks the owner callback.
        try (InputStream resource = plugin.getResource(RULES_RESOURCE)) {
            if (resource != null)
                throw new IOException("Configuration archive owner is unavailable for " + plugin.getName()
                        + ". Update that plugin before importing content; its policy was not read.");
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot safely archive outdated " + plugin.getName() + " configuration", failure);
        }
    }

    /**
     * Stable service identity across relocation. Registration is tied to Bukkit's plugin object,
     * and retained callbacks are revoked at shutdown. Concurrent importers share the owner's lock.
     */
    private static final class OutdatedConfigurationArchiveOwner implements Runnable {
        private final JavaPlugin plugin;
        private volatile boolean active = true;

        private OutdatedConfigurationArchiveOwner(JavaPlugin plugin) { this.plugin = plugin; }

        @Override public synchronized void run() {
            if (!active || !plugin.isEnabled())
                throw new IllegalStateException("Configuration archive owner is disabled: " + plugin.getName());
            archive(plugin);
        }
    }

    /** Owner-local implementation. The resource is bundled in this plugin's JAR. */
    static void archive(JavaPlugin plugin) {
        try (InputStream resource = plugin.getResource(RULES_RESOURCE)) {
            if (resource == null) return;
            Map<String, Set<Rule>> rules = readRules(readBounded(resource));
            Path data = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
            Path storage = data.getParent().resolve("MagmaCore").resolve(DIRECTORY);
            List<Path> archived = archive(data, storage, rules);
            if (!archived.isEmpty()) plugin.getLogger().warning("Archived " + archived.size()
                    + " outdated configuration file(s) to " + storage.resolve(data.getFileName())
                    + ". Their original contents were preserved. Download updated content to replace retired DLC files.");
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot safely archive outdated " + plugin.getName() + " configuration", failure);
        }
    }

    sealed interface Rule permits KeyRule, ListEntryRule, ValueRule, LuaScriptRule {
        boolean matches(String filename, Map<?, ?> yaml);
    }

    /** A frozen script contract, not a runtime provider or a list of retired filenames. */
    record LuaScriptRule(Set<String> supportedHooks, Set<String> retiredHooks) implements Rule {
        LuaScriptRule {
            supportedHooks = Set.copyOf(supportedHooks);
            retiredHooks = Set.copyOf(retiredHooks);
        }

        @Override public boolean matches(String filename, Map<?, ?> yaml) { return false; }

        boolean matchesScript(Path path, byte[] source) {
            ScriptProvider contract = new ScriptProvider() {
                @Override public String getNamespace() { return "archive"; }
                @Override public Path getScriptDirectory() { return path.getParent(); }
                @Override public ScriptHook resolveHook(String key) {
                    return supportedHooks.contains(key) || retiredHooks.contains(key) ? new ScriptHook(key) : null;
                }
            };
            try {
                // Reuse the sandbox and execution budget used by normal script validation.
                // No handlers are called and this provider is never registered for dispatch.
                ScriptDefinition definition = ScriptDefinition.validate(path.getFileName().toString(), path.toFile(),
                        StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source)).toString().replace("\r", ""), contract);
                return definition.getHooks().stream().anyMatch(hook -> retiredHooks.contains(hook.getKey()));
            } catch (RuntimeException | CharacterCodingException ambiguous) {
                // Syntax errors, unknown fields, bad handlers and evaluation/budget failures
                // are not evidence of a retired format. Leave them for the normal loader.
                return false;
            }
        }
    }

    record KeyRule(String key) implements Rule {
        @Override public boolean matches(String filename, Map<?, ?> yaml) {
            return yaml.containsKey(key);
        }
    }

    record ValueRule(Set<String> files, List<String> path, String value) implements Rule {
        ValueRule {
            files = Set.copyOf(files);
            path = List.copyOf(path);
        }

        @Override public boolean matches(String filename, Map<?, ?> yaml) {
            if (!files.contains(filename)) return false;
            Object current = yaml;
            for (String key : path) {
                if (!(current instanceof Map<?, ?> mapping)) return false;
                current = mapping.get(key);
            }
            return value.equals(current);
        }
    }

    record ListEntryRule(Set<String> files, String key, Set<String> names) implements Rule {
        ListEntryRule {
            files = Set.copyOf(files);
            names = Set.copyOf(names);
        }

        @Override public boolean matches(String filename, Map<?, ?> yaml) {
            if (!files.contains(filename) || !(yaml.get(key) instanceof List<?> entries)) return false;
            for (Object entry : entries) {
                if (!(entry instanceof String text)) continue;
                int comma = text.indexOf(',');
                if (comma < 0) continue;
                if (names.contains(text.substring(0, comma).toLowerCase(Locale.ROOT))) return true;
            }
            return false;
        }
    }

    static Map<String, Set<Rule>> readRules(byte[] bytes) throws IOException {
        Map<?, ?> yaml = readMapping(bytes);
        Map<String, Set<Rule>> result = new LinkedHashMap<>();
        for (var entry : yaml.entrySet()) {
            if (!(entry.getKey() instanceof String category)
                    || !category.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*|[A-Za-z0-9_-]+\\.ya?ml"))
                throw new IOException("An outdated configuration category must be a relative content directory or a root YAML filename");
            if (!(entry.getValue() instanceof List<?> rules) || rules.isEmpty())
                throw new IOException("An outdated configuration category requires nonempty rules: " + category);
            Set<Rule> selected = new LinkedHashSet<>();
            for (Object rule : rules) {
                if (rule instanceof String key && !key.isBlank()) selected.add(new KeyRule(key));
                else if (rule instanceof Map<?, ?> fields
                        && fields.keySet().equals(Set.of("files", "key", "listEntryNames"))
                        && fields.get("key") instanceof String key && !key.isBlank()) {
                    Set<String> files = readNames(fields.get("files"), "files", "[A-Za-z0-9_-]+\\.ya?ml");
                    Set<String> names = readNames(fields.get("listEntryNames"), "listEntryNames", "[a-z0-9_.:-]+");
                    selected.add(new ListEntryRule(files, key, names));
                } else if (rule instanceof Map<?, ?> fields
                        && fields.keySet().equals(Set.of("files", "key", "value"))
                        && fields.get("key") instanceof String key
                        && key.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")
                        && fields.get("value") instanceof String value && !value.isBlank()) {
                    Set<String> files = readNames(fields.get("files"), "files", "[A-Za-z0-9_-]+\\.ya?ml");
                    selected.add(new ValueRule(files, List.of(key.split("\\.")), value));
                } else if (rule instanceof Map<?, ?> fields && fields.keySet().equals(Set.of("lua"))
                        && fields.get("lua") instanceof Map<?, ?> lua
                        && lua.keySet().equals(Set.of("supportedHooks", "retiredHooks"))) {
                    if (category.endsWith(".yml") || category.endsWith(".yaml"))
                        throw new IOException("A Lua retirement rule requires a script directory: " + category);
                    Set<String> supported = readNames(lua.get("supportedHooks"), "supportedHooks", "on_[a-z_]+");
                    Set<String> retired = readNames(lua.get("retiredHooks"), "retiredHooks", "on_[a-z_]+");
                    if (supported.stream().anyMatch(retired::contains))
                        throw new IOException("Supported and retired Lua hooks must not overlap: " + category);
                    selected.add(new LuaScriptRule(supported, retired));
                } else throw new IOException("Invalid outdated configuration rule in " + category);
            }
            result.put(category, Set.copyOf(selected));
        }
        return Map.copyOf(result);
    }

    private static Set<String> readNames(Object value, String field, String pattern) throws IOException {
        if (!(value instanceof List<?> entries) || entries.isEmpty())
            throw new IOException("An outdated configuration rule requires nonempty " + field);
        Set<String> names = new LinkedHashSet<>();
        for (Object entry : entries) {
            if (!(entry instanceof String name) || !name.matches(pattern))
                throw new IOException("Invalid outdated configuration " + field + " entry: " + entry);
            names.add(name);
        }
        return Set.copyOf(names);
    }

    /** Package-private filesystem seam also used by focused upgrade tests. */
    static List<Path> archive(Path dataDirectory, Path archiveDirectory,
                              Map<String, Set<Rule>> rules) throws IOException {
        Path data = dataDirectory.toAbsolutePath().normalize();
        Path storage = archiveDirectory.toAbsolutePath().normalize();
        if (storage.startsWith(data)) throw new IOException("Archive storage must be outside the content root");
        if (!Files.exists(data, LinkOption.NOFOLLOW_LINKS) || rules.isEmpty()) return List.of();
        requireNoLinks(data);
        Map<Path, byte[]> matches = new LinkedHashMap<>();
        // Finish classification before any move. Invalid YAML still aborts the batch;
        // unclassifiable Lua stays in place for the script loader's normal diagnostics.
        for (var rule : rules.entrySet()) {
            Path category = data.resolve(rule.getKey()).normalize();
            if (!category.startsWith(data) || category.equals(data)) throw new IOException("Invalid content category");
            if (!Files.exists(category, LinkOption.NOFOLLOW_LINKS)) continue;
            requireNoLinks(category);
            if ((rule.getKey().endsWith(".yml") || rule.getKey().endsWith(".yaml"))
                    && !Files.isRegularFile(category, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Not a regular YAML file: " + category);
            List<LuaScriptRule> luaRules = rule.getValue().stream().filter(LuaScriptRule.class::isInstance)
                    .map(LuaScriptRule.class::cast).toList();
            List<Rule> yamlRules = rule.getValue().stream().filter(selected -> !(selected instanceof LuaScriptRule)).toList();
            try (var paths = Files.walk(category)) {
                for (Path path : paths.sorted().toList()) {
                    requireNoLinks(path);
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    boolean lua = name.endsWith(".lua") && !luaRules.isEmpty();
                    boolean yaml = (name.endsWith(".yml") || name.endsWith(".yaml")) && !yamlRules.isEmpty();
                    if (!lua && !yaml) continue;
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular configuration file: " + path);
                    byte[] original;
                    try (InputStream input = Files.newInputStream(path)) {
                        original = readBounded(input);
                    } catch (IOException unreadable) {
                        if (lua) continue;
                        throw unreadable;
                    }
                    // Classify actual parsed definitions, never comments or text fragments.
                    if (lua) {
                        if (luaRules.stream().anyMatch(selected -> selected.matchesScript(path, original)))
                            matches.put(path, original);
                    } else if (matchesYaml(name, original, yamlRules)) {
                        matches.put(path, original);
                    }
                }
            }
        }
        if (matches.isEmpty()) return List.of();
        requireNoLinks(storage);
        Files.createDirectories(storage);
        requireNoLinks(storage);
        Path owner = storage.resolve(data.getFileName());
        requireNoLinks(owner);
        Files.createDirectories(owner);
        Path batch = owner.resolve(Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID());
        Files.createDirectory(batch);
        List<Path> archived = new ArrayList<>();
        for (var entry : matches.entrySet()) {
            Path source = entry.getKey();
            requireNoLinks(source);
            byte[] current;
            try (InputStream input = Files.newInputStream(source)) { current = readBounded(input); }
            if (!Arrays.equals(entry.getValue(), current)) throw new IOException("Configuration changed during archival: " + source);
            Path destination = batch.resolve(data.relativize(source));
            requireNoLinks(destination);
            Files.createDirectories(destination.getParent());
            requireNoLinks(destination);
            // No REPLACE_EXISTING: earlier archives and collisions must never be overwritten.
            Files.move(source, destination);
            archived.add(destination);
        }
        return List.copyOf(archived);
    }

    private static boolean matchesYaml(String name, byte[] source, List<Rule> rules) throws IOException {
        Map<?, ?> yaml = readMapping(source);
        return rules.stream().anyMatch(rule -> rule.matches(name, yaml));
    }

    public static boolean isArchivePath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        for (int index = 1; index < absolute.getNameCount(); index++)
            if (absolute.getName(index).toString().equalsIgnoreCase(DIRECTORY)
                    && absolute.getName(index - 1).toString().equalsIgnoreCase("MagmaCore")) return true;
        return false;
    }

    private static void requireNoLinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path parent = absolute; parent != null; parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) throw new IOException("Refusing linked archive/content path: " + parent);
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
                    && !parent.toRealPath().equals(parent))
                throw new IOException("Refusing redirected archive/content path: " + parent);
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_SOURCE_BYTES + 1);
        if (bytes.length > MAX_SOURCE_BYTES) throw new IOException("Configuration exceeds the archival inspection limit");
        return bytes;
    }

    private static Map<?, ?> readMapping(byte[] bytes) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(MAX_SOURCE_BYTES);
        try {
            Object value = new Yaml(new SafeConstructor(options)).load(new String(bytes, StandardCharsets.UTF_8));
            if (value == null) return Map.of();
            if (!(value instanceof Map<?, ?> mapping)) throw new IOException("Configuration must be a YAML mapping");
            return mapping;
        } catch (RuntimeException failure) {
            throw new IOException("Cannot inspect YAML for retired keys", failure);
        }
    }
}
