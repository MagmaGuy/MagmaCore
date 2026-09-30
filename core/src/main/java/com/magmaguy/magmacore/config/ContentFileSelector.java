package com.magmaguy.magmacore.config;

import com.magmaguy.magmacore.util.Logger;

import java.io.File;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Selects one source per filename before a content catalog parses or modifies files. */
public final class ContentFileSelector {
    private ContentFileSelector() {
    }

    public static List<File> select(Collection<File> candidates) {
        return select(candidates, UnaryOperator.identity());
    }

    /**
     * Uses stable absolute-path order, regardless of directory enumeration order or file contents.
     * The key function must match the catalog's existing filename lookup rules.
     * Selection never deletes or rewrites files, and never falls back to a skipped duplicate.
     */
    public static List<File> select(Collection<File> candidates, UnaryOperator<String> filenameKey) {
        Comparator<File> order = Comparator.comparing(ContentFileSelector::absolutePath,
                String.CASE_INSENSITIVE_ORDER).thenComparing(ContentFileSelector::absolutePath);
        Map<String, File> selected = new LinkedHashMap<>();
        for (File file : candidates.stream().sorted(order).toList()) {
            if (OutdatedConfigurationArchive.isArchivePath(file.toPath())) continue;
            File previous = selected.putIfAbsent(filenameKey.apply(file.getName()), file);
            // Content packages may ship the same shared file; a byte-identical copy loads the same definition either way.
            if (previous != null && !absolutePath(previous).equals(absolutePath(file)) && !sameContents(previous, file))
                Logger.warn("Duplicate content filename '" + file.getName() + "': selected "
                        + absolutePath(previous) + "; skipped " + absolutePath(file)
                        + ". Only the selected file will be loaded. Resolve the duplicate filenames.");
        }
        return List.copyOf(selected.values());
    }

    /**
     * True when the skipped copy says nothing the selected copy does not already say. Plugins rewrite the file they
     * load (key order, added defaults), so two YAML copies that shipped identical are compared by value: every value the
     * skipped copy sets must be present and equal in the selected copy. Other files must match byte for byte.
     */
    private static boolean sameContents(File selected, File skipped) {
        try {
            if (java.nio.file.Files.mismatch(selected.toPath(), skipped.toPath()) == -1L) return true;
        } catch (java.io.IOException unreadable) {
            return false;
        }
        String name = skipped.getName().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".yml") && !name.endsWith(".yaml")) return false;
        try {
            var selectedValues = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(selected);
            var skippedValues = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(skipped).getValues(true);
            if (skippedValues.isEmpty()) return false;
            for (Map.Entry<String, Object> entry : skippedValues.entrySet()) {
                if (entry.getValue() instanceof org.bukkit.configuration.ConfigurationSection) continue;
                if (!java.util.Objects.equals(selectedValues.get(entry.getKey()), entry.getValue())) return false;
            }
            return true;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static String absolutePath(File file) {
        return file.toPath().toAbsolutePath().normalize().toString();
    }
}
