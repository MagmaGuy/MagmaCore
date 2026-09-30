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

    private static boolean sameContents(File first, File second) {
        try {
            return java.nio.file.Files.mismatch(first.toPath(), second.toPath()) == -1L;
        } catch (java.io.IOException unreadable) {
            return false;
        }
    }

    private static String absolutePath(File file) {
        return file.toPath().toAbsolutePath().normalize().toString();
    }
}
