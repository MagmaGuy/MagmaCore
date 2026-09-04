package com.magmaguy.magmacore.config;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Opt-in policy for sparse YAML inheritance. MagmaCore deliberately knows nothing about consumer keys. */
@FunctionalInterface
public interface CustomConfigInheritancePolicy {
    boolean mayInherit(String path);

    default String parentKey() {
        return "extends";
    }

    static CustomConfigInheritancePolicy excludingRoots(Collection<String> excludedRoots) {
        Set<String> normalized = excludedRoots.stream()
                .map(root -> root.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return path -> {
            String lowerPath = path.toLowerCase(Locale.ROOT);
            int separator = lowerPath.indexOf('.');
            String root = separator < 0 ? lowerPath : lowerPath.substring(0, separator);
            return !normalized.contains(root);
        };
    }
}
