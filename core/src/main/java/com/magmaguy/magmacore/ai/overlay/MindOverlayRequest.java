package com.magmaguy.magmacore.ai.overlay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What to lay over a mob's own AI: the behavior modules that drive it, in the order listed, whom it serves, for
 * how long, and the settings that differ from each module's defaults.
 *
 * @param behaviors     module names, the file names in the behaviors folder without {@code .lua}; at least one
 * @param owner         the player or other living entity the modules see as {@code c.overlay.owner}, whom the mob
 *                      can never hurt; null for a mob that serves nobody
 * @param durationTicks how long the overlay lasts; 0 lasts until something clears it
 * @param settings      per module name, the settings that replace its defaults; a module left out keeps them all
 */
public record MindOverlayRequest(
        List<String> behaviors,
        UUID owner,
        long durationTicks,
        Map<String, Map<String, Object>> settings) {

    public MindOverlayRequest {
        Objects.requireNonNull(behaviors, "behaviors");
        List<String> names = new ArrayList<>(behaviors.size());
        for (String behavior : behaviors) {
            String name = normalize(Objects.requireNonNull(behavior, "behavior"));
            if (name.isEmpty()) throw new IllegalArgumentException("A mob behavior needs a name");
            if (!names.contains(name)) names.add(name);
        }
        if (names.isEmpty()) throw new IllegalArgumentException("An overlay needs at least one mob behavior");
        behaviors = List.copyOf(names);
        if (durationTicks < 0L) throw new IllegalArgumentException("An overlay cannot last a negative time");
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        if (settings != null) {
            settings.forEach((module, values) -> {
                if (module != null && values != null && !values.isEmpty()) {
                    copy.put(normalize(module), Collections.unmodifiableMap(new LinkedHashMap<>(values)));
                }
            });
        }
        settings = Collections.unmodifiableMap(copy);
    }

    /** These behaviors until something clears them, serving nobody, with their default settings. */
    public static MindOverlayRequest of(List<String> behaviors) {
        return new MindOverlayRequest(behaviors, null, 0L, Map.of());
    }

    public MindOverlayRequest ownedBy(UUID owner) {
        return new MindOverlayRequest(behaviors, owner, durationTicks, settings);
    }

    public MindOverlayRequest lasting(long durationTicks) {
        return new MindOverlayRequest(behaviors, owner, durationTicks, settings);
    }

    public MindOverlayRequest withSettings(Map<String, Map<String, Object>> settings) {
        return new MindOverlayRequest(behaviors, owner, durationTicks, settings);
    }

    /** A module name as the catalog knows it: lower case, without the file extension. */
    static String normalize(String name) {
        String trimmed = name.trim().toLowerCase(Locale.ROOT);
        return trimmed.endsWith(".lua") ? trimmed.substring(0, trimmed.length() - 4) : trimmed;
    }
}
