package com.magmaguy.magmacore.config;

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
import java.util.UUID;

/** Retires whole YAML files by explicit category/key rules, without rewriting their contents. */
public final class OutdatedConfigurationArchive {
    public static final String RULES_RESOURCE = "outdated-config-keys.yml";
    public static final String DIRECTORY = "outdated files";
    private static final int MAX_YAML_BYTES = 4 * 1024 * 1024;

    private OutdatedConfigurationArchive() { }

    /** The resource is bundled in the plugin JAR, not an administrator configuration. */
    public static void archive(JavaPlugin plugin) {
        try (InputStream resource = plugin.getResource(RULES_RESOURCE)) {
            if (resource == null) return;
            Map<String, Set<String>> rules = readRules(readBounded(resource));
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

    static Map<String, Set<String>> readRules(byte[] bytes) throws IOException {
        Map<?, ?> yaml = readMapping(bytes);
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (var entry : yaml.entrySet()) {
            if (!(entry.getKey() instanceof String category)
                    || !category.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"))
                throw new IOException("An outdated configuration category must be a relative content directory");
            if (!(entry.getValue() instanceof List<?> keys) || keys.isEmpty()
                    || keys.stream().anyMatch(key -> !(key instanceof String text) || text.isBlank()))
                throw new IOException("An outdated configuration category requires nonempty YAML keys: " + category);
            result.put(category, Set.copyOf(keys.stream().map(String.class::cast).toList()));
        }
        return Map.copyOf(result);
    }

    /** Package-private filesystem seam also used by focused upgrade tests. */
    static List<Path> archive(Path dataDirectory, Path archiveDirectory,
                              Map<String, Set<String>> rules) throws IOException {
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
                    // Exact top-level keys, including explicit null/false/empty values. Never search comments or strings.
                    if (rule.getValue().stream().anyMatch(yaml::containsKey)) matches.put(path, original);
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
