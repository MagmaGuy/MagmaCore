package com.magmaguy.magmacore.config;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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

/** Retires whole YAML files by explicit category/key rules, without rewriting their contents. */
public final class OutdatedConfigurationArchive {
    public static final String RULES_RESOURCE = "outdated-config-keys.yml";
    public static final String DIRECTORY = "outdated files";
    private static final int MAX_YAML_BYTES = 4 * 1024 * 1024;
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

    sealed interface Rule permits KeyRule, ListEntryRule, ValueRule {
        boolean matches(String filename, Map<?, ?> yaml);
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
                    || !category.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"))
                throw new IOException("An outdated configuration category must be a relative content directory");
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
        // Finish parsing before any move. Invalid YAML cannot cause a partial scan to become an archive.
        for (var rule : rules.entrySet()) {
            Path category = data.resolve(rule.getKey()).normalize();
            if (!category.startsWith(data) || category.equals(data)) throw new IOException("Invalid content category");
            if (!Files.exists(category, LinkOption.NOFOLLOW_LINKS)) continue;
            requireNoLinks(category);
            try (var paths = Files.walk(category)) {
                for (Path path : paths.sorted().toList()) {
                    requireNoLinks(path);
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            || !(name.endsWith(".yml") || name.endsWith(".yaml"))) continue;
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular YAML file: " + path);
                    byte[] original;
                    try (InputStream input = Files.newInputStream(path)) { original = readBounded(input); }
                    Map<?, ?> yaml = readMapping(original);
                    // Rules inspect explicit parsed fields, never comments or unrelated string contents.
                    if (rule.getValue().stream().anyMatch(selected -> selected.matches(name, yaml)))
                        matches.put(path, original);
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
        byte[] bytes = input.readNBytes(MAX_YAML_BYTES + 1);
        if (bytes.length > MAX_YAML_BYTES) throw new IOException("YAML exceeds the archival inspection limit");
        return bytes;
    }

    private static Map<?, ?> readMapping(byte[] bytes) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(MAX_YAML_BYTES);
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
