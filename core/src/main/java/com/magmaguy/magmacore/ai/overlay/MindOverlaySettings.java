package com.magmaguy.magmacore.ai.overlay;

import com.magmaguy.magmacore.scripting.LuaMindModuleDescriptor;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A module's settings as one mob sees them: the defaults the module declares, with the values an overlay gives in
 * their place. A value takes the type of the default it replaces; one that cannot is refused and the default kept.
 */
final class MindOverlaySettings {
    private MindOverlaySettings() {
    }

    /**
     * @param modules   every module of the program, by id
     * @param idPrefix  what a behavior's name is prefixed with to make its module id
     * @param overrides per behavior name, the values that replace its defaults
     * @return per module id, its settings for this overlay
     */
    static Map<String, Map<String, Object>> merge(
            Map<String, LuaMindModuleDescriptor> modules,
            String idPrefix,
            Map<String, Map<String, Object>> overrides,
            Consumer<String> warn) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        modules.forEach((id, module) -> merged.put(id, new LinkedHashMap<>(module.settings())));
        overrides.forEach((name, values) -> {
            Map<String, Object> target = merged.get(idPrefix + name);
            if (target == null) {
                warn.accept("Settings were given for mob behavior '" + name + "', which this overlay does not run.");
                return;
            }
            values.forEach((key, value) -> {
                Object fallback = target.get(key);
                if (fallback == null) {
                    warn.accept("Mob behavior '" + name + "' has no setting '" + key + "'.");
                    return;
                }
                Object coerced = coerce(fallback, value);
                if (coerced == null) {
                    warn.accept("Setting '" + key + "' of mob behavior '" + name + "' cannot be '" + value
                            + "'; it stays " + fallback + ".");
                    return;
                }
                target.put(key, coerced);
            });
        });
        return merged;
    }

    /** The value as the type of the default it replaces, or null when it is not one. */
    private static Object coerce(Object fallback, Object value) {
        if (value == null) return null;
        if (fallback instanceof Double) {
            if (value instanceof Number number) {
                double number64 = number.doubleValue();
                return Double.isFinite(number64) ? number64 : null;
            }
            try {
                double parsed = Double.parseDouble(value.toString().trim());
                return Double.isFinite(parsed) ? parsed : null;
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        if (fallback instanceof Boolean) {
            if (value instanceof Boolean) return value;
            String text = value.toString().trim().toLowerCase(Locale.ROOT);
            return text.equals("true") ? Boolean.TRUE : text.equals("false") ? Boolean.FALSE : null;
        }
        if (fallback instanceof String) {
            return value instanceof List<?> || value instanceof Map<?, ?> ? null : value.toString();
        }
        if (fallback instanceof List<?>) {
            if (!(value instanceof List<?> list)) return value instanceof Map<?, ?> ? null : List.of(value.toString());
            List<String> texts = new ArrayList<>(list.size());
            for (Object entry : list) {
                if (entry == null || entry instanceof List<?> || entry instanceof Map<?, ?>) return null;
                texts.add(entry.toString());
            }
            return List.copyOf(texts);
        }
        return null;
    }

    /** A fresh table of one module's settings: numbers, booleans, text and lists of text. */
    static LuaTable toLua(Map<String, Object> settings) {
        LuaTable table = new LuaTable(0, settings.size());
        settings.forEach((key, value) -> table.rawset(key, toLua(value)));
        return table;
    }

    private static LuaValue toLua(Object value) {
        if (value instanceof Double number) return LuaValue.valueOf(number);
        if (value instanceof Boolean flag) return LuaValue.valueOf(flag);
        if (value instanceof List<?> list) {
            LuaTable array = new LuaTable(list.size(), 0);
            for (int index = 0; index < list.size(); index++) {
                array.rawset(index + 1, LuaValue.valueOf(String.valueOf(list.get(index))));
            }
            return array;
        }
        return LuaValue.valueOf(String.valueOf(value));
    }
}
