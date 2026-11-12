package com.magmaguy.magmacore.ai.overlay;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * An overlay as it is written on its mob, so the mob has it again after its chunk unloads or the server restarts:
 * the request, with the world game time it ends at in place of how long it lasts.
 *
 * @param endsAt the world's game time the overlay ends at; 0 for one that lasts until cleared
 */
record MindOverlayRecord(MindOverlayRequest request, long endsAt) {

    String encode() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("behaviors", request.behaviors());
        if (request.owner() != null) yaml.set("owner", request.owner().toString());
        if (endsAt > 0L) yaml.set("ends", endsAt);
        request.settings().forEach((module, values) ->
                values.forEach((key, value) -> yaml.set("settings." + escape(module) + "." + escape(key), value)));
        return yaml.saveToString();
    }

    /** Empty when the text is not a record this version can read. */
    static Optional<MindOverlayRecord> decode(String encoded) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(encoded);
            List<String> behaviors = yaml.getStringList("behaviors");
            if (behaviors.isEmpty()) return Optional.empty();
            String owner = yaml.getString("owner");
            Map<String, Map<String, Object>> settings = new LinkedHashMap<>();
            ConfigurationSection modules = yaml.getConfigurationSection("settings");
            if (modules != null) {
                for (String module : modules.getKeys(false)) {
                    ConfigurationSection values = modules.getConfigurationSection(module);
                    if (values == null) continue;
                    Map<String, Object> read = new LinkedHashMap<>();
                    for (String key : values.getKeys(false)) read.put(unescape(key), values.get(key));
                    settings.put(unescape(module), read);
                }
            }
            return Optional.of(new MindOverlayRecord(
                    new MindOverlayRequest(behaviors, owner == null ? null : UUID.fromString(owner), 0L, settings),
                    Math.max(0L, yaml.getLong("ends", 0L))));
        } catch (InvalidConfigurationException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    // A dot separates the levels of a YAML path, and module names may have dots.
    private static String escape(String name) {
        return name.replace(".", "%2E");
    }

    private static String unescape(String name) {
        return name.replace("%2E", ".");
    }
}
