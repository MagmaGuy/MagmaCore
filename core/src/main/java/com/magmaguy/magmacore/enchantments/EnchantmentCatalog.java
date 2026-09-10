package com.magmaguy.magmacore.enchantments;

import com.magmaguy.magmacore.config.ContentFileSelector;
import com.magmaguy.magmacore.scripting.ScriptDefinition;
import com.magmaguy.magmacore.scripting.ScriptHook;
import com.magmaguy.magmacore.scripting.ScriptProvider;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Builds a complete candidate catalog. Failure leaves the host's previous catalog untouched. */
public final class EnchantmentCatalog {
    private static final Set<String> FIELDS = Set.of("isEnabled", "name", "description", "maxLevel", "curse",
            "validSlots", "itemTypes", "attackKinds", "requires", "conflicts", "stacking", "script", "parameters");
    private static final long MAX_SOURCE_BYTES = 256 * 1024;
    private final String namespace;
    private final Map<String, EnchantmentDefinition> definitions;
    private final Map<String, ScriptDefinition> scripts;

    private EnchantmentCatalog(String namespace, Map<String, EnchantmentDefinition> definitions, Map<String, ScriptDefinition> scripts) {
        this.namespace = namespace;
        this.definitions = Map.copyOf(definitions);
        this.scripts = Map.copyOf(scripts);
    }

    public Map<String, EnchantmentDefinition> definitions() { return definitions; }
    public String namespace() { return namespace; }
    public Optional<ScriptDefinition> script(String id) { return Optional.ofNullable(scripts.get(id)); }

    public static EnchantmentCatalog load(String namespace, Path directory, Set<ScriptHook> supportedHooks) throws IOException {
        return load(namespace, directory, supportedHooks, path -> true);
    }

    /** Selection runs after duplicate resolution, so hosts cannot load a rejected duplicate through another owner. */
    public static EnchantmentCatalog load(String namespace, Path directory, Set<ScriptHook> supportedHooks,
                                         java.util.function.Predicate<Path> ownsDefinition) throws IOException {
        EnchantmentDefinition.requireId(namespace + ":catalog");
        if (namespace.equals("minecraft")) throw new IllegalArgumentException("Custom catalogs cannot own minecraft");
        Path root = directory.toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Enchantment root is not a directory: " + root);
        Map<String, ScriptHook> hooks = new LinkedHashMap<>();
        for (ScriptHook hook : supportedHooks)
            if (hooks.putIfAbsent(hook.getKey(), hook) != null) throw new IllegalArgumentException("Duplicate hook");
        ScriptProvider provider = new ScriptProvider() {
            public String getNamespace() { return namespace; }
            public Path getScriptDirectory() { return root; }
            public ScriptHook resolveHook(String key) { return hooks.get(key); }
        };
        List<File> candidates = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                requireContained(root, dir);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".lua")) {
                    requireContained(root, file);
                    if (!Files.isRegularFile(file)) throw new IOException("Not a regular content file: " + file);
                    candidates.add(file.toFile());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        Map<String, Path> selected = new LinkedHashMap<>();
        for (File file : ContentFileSelector.select(candidates, name -> name.toLowerCase(Locale.ROOT)))
            selected.put(file.getName().toLowerCase(Locale.ROOT), file.toPath());
        Map<String, EnchantmentDefinition> definitions = new LinkedHashMap<>();
        Map<String, ScriptDefinition> scripts = new LinkedHashMap<>();
        Map<String, ScriptDefinition> validatedScripts = new LinkedHashMap<>();
        for (var entry : selected.entrySet()) {
            if (entry.getKey().endsWith(".lua")) continue;
            if (!ownsDefinition.test(entry.getValue())) continue;
            String filename = entry.getKey();
            String id = namespace + ":" + filename.substring(0, filename.lastIndexOf('.'));
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(readSource(root, entry.getValue()));
                Set<String> unknown = new LinkedHashSet<>(yaml.getKeys(false));
                unknown.removeAll(FIELDS);
                if (!unknown.isEmpty()) throw new IllegalArgumentException("Unknown fields: " + unknown);
                if (!bool(yaml, "isEnabled", true)) continue;
                EnchantmentDefinition definition = new EnchantmentDefinition(id, string(yaml, "name", null),
                        string(yaml, "description", ""), integer(yaml, "maxLevel"), bool(yaml, "curse", false),
                        enums(yaml, "validSlots", EnchantmentDefinition.Slot.class),
                        enums(yaml, "itemTypes", EnchantmentDefinition.ItemType.class), strings(yaml, "attackKinds"),
                        strings(yaml, "requires"), strings(yaml, "conflicts"), stacking(yaml),
                        string(yaml, "script", null), parameters(yaml));
                if (definitions.putIfAbsent(id, definition) != null)
                    throw new IllegalArgumentException("Both .yml and .yaml define the same identity: " + id);
                ScriptDefinition script = validatedScripts.get(definition.script());
                if (script == null) {
                    Path source = selected.get(definition.script());
                    if (source == null) throw new IllegalArgumentException("Missing selected script: " + definition.script());
                    script = ScriptDefinition.validate(definition.script(), source.toFile(), readSource(root, source), provider);
                    validatedScripts.put(definition.script(), script);
                }
                scripts.put(id, script);
            } catch (InvalidConfigurationException | RuntimeException failure) {
                throw new IOException("Invalid enchantment definition " + entry.getValue() + ": " + failure.getMessage(), failure);
            }
        }
        return new EnchantmentCatalog(namespace, definitions, scripts);
    }

    private static void requireContained(Path root, Path path) throws IOException {
        if (!path.toRealPath().startsWith(root)) throw new IOException("Enchantment content escapes its root: " + path);
    }

    private static String readSource(Path root, Path path) throws IOException {
        requireContained(root, path);
        if (Files.size(path) > MAX_SOURCE_BYTES) throw new IOException("Enchantment source exceeds 256 KiB: " + path);
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes((int) MAX_SOURCE_BYTES + 1);
            if (bytes.length > MAX_SOURCE_BYTES) throw new IOException("Enchantment source exceeds 256 KiB: " + path);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String string(ConfigurationSection section, String key, String fallback) {
        Object value = section.get(key);
        if (value == null && fallback != null) return fallback;
        if (!(value instanceof String text)) throw new IllegalArgumentException(key + " must be a string");
        return text;
    }

    private static boolean bool(ConfigurationSection section, String key, boolean fallback) {
        Object value = section.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException(key + " must be a boolean");
        return result;
    }

    private static int integer(ConfigurationSection section, String key) {
        if (!(section.get(key) instanceof Integer value)) throw new IllegalArgumentException(key + " must be an integer");
        return value;
    }

    private static Set<String> strings(ConfigurationSection section, String key) {
        Object value = section.get(key);
        if (value == null) return Set.of();
        if (!(value instanceof List<?> values) || values.size() > 128) throw new IllegalArgumentException(key + " must be a bounded list");
        Set<String> result = new LinkedHashSet<>();
        for (Object element : values) {
            if (!(element instanceof String text) || text.isBlank() || !result.add(text))
                throw new IllegalArgumentException(key + " must contain unique nonempty strings");
        }
        return Set.copyOf(result);
    }

    private static <E extends Enum<E>> Set<E> enums(ConfigurationSection section, String key, Class<E> type) {
        Set<E> result = new LinkedHashSet<>();
        for (String name : strings(section, key)) result.add(Enum.valueOf(type, name));
        return Set.copyOf(result);
    }

    private static EnchantmentDefinition.Stacking stacking(ConfigurationSection section) {
        return switch (string(section, "stacking", "source_item")) {
            case "source_item" -> EnchantmentDefinition.Stacking.SOURCE_ITEM;
            case "sum_equipped_levels" -> EnchantmentDefinition.Stacking.SUM_EQUIPPED_LEVELS;
            default -> throw new IllegalArgumentException("Unknown stacking policy");
        };
    }

    private static Map<String, Object> parameters(ConfigurationSection section) {
        Object raw = section.get("parameters");
        if (raw == null) return Map.of();
        if (!(raw instanceof ConfigurationSection parameters)) throw new IllegalArgumentException("parameters must be a mapping");
        return parameters.getValues(false);
    }
}
