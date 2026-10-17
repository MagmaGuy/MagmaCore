package com.magmaguy.magmacore.enchantments;

import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import static com.magmaguy.magmacore.enchantments.EnchantmentDefinition.Slot;

/**
 * Item sets are enchantments whose definition lists piece slots ({@link ItemSetDefinition}). A set is complete
 * while every piece slot holds an item carrying its enchantment. Its hooks then run once per input; an
 * incomplete set runs nothing.
 * <p>
 * Set lore templates take these placeholders: {@code $setName}, {@code $equippedPieces}, {@code $totalPieces},
 * {@code $setPieces} (one line per piece from equippedPiece or missingPiece, which take {@code $pieceName} and
 * {@code $pieceSlot}), and the line prefixes {@code $ifSetComplete} and {@code $ifSetIncomplete}.
 * Hosts place the section with {@link #presentation}; equipment changes redraw it with {@link #refresh}.
 */
public final class ItemSets {
    static final NamespacedKey PRESENTATION = EnchantmentItemData.key("item_set_presentation");
    private static final NamespacedKey LINES = EnchantmentItemData.key("lines");

    private ItemSets() { }

    /** A complete worn set and the level it runs at, the lowest level among its pieces. */
    public record Active(EnchantmentItems.Resolved resolved, int level) {
        public String id() { return resolved.definition().id(); }
        public ItemSetDefinition set() { return resolved.definition().set(); }
    }

    /** Filled piece slots of each set present in captured equipment, keyed by set enchantment id. */
    public static Map<String, Set<Slot>> equippedPieces(Map<String, ItemStack> equipment,
                                                        Function<String, EnchantmentItems.Resolved> resolver) {
        Map<String, Set<Slot>> worn = new TreeMap<>();
        for (var entry : equipment.entrySet()) {
            Slot slot = Slot.valueOf(entry.getKey());
            for (String id : custom(entry.getValue()).keySet()) {
                var resolved = resolve(resolver, id);
                if (resolved == null || !resolved.definition().isSet()
                        || !resolved.definition().set().pieces().containsKey(slot)) continue;
                worn.computeIfAbsent(id, ignored -> EnumSet.noneOf(Slot.class)).add(slot);
            }
        }
        return worn;
    }

    /** Complete sets in captured equipment, in identity order. */
    public static List<Active> active(Map<String, ItemStack> equipment, Function<String, EnchantmentItems.Resolved> resolver) {
        List<Active> active = new ArrayList<>();
        for (var entry : equippedPieces(equipment, resolver).entrySet()) {
            var resolved = resolve(resolver, entry.getKey());
            if (resolved == null || !entry.getValue().containsAll(resolved.definition().set().pieces().keySet())) continue;
            int level = Integer.MAX_VALUE;
            for (Slot slot : resolved.definition().set().pieces().keySet())
                level = Math.min(level, custom(equipment.get(slot.name())).get(entry.getKey()));
            active.add(new Active(resolved, level));
        }
        return active;
    }

    /**
     * For hosts that read equipment slot by slot: ordinary enchantments count at every slot, while a set
     * counts once, at the first of its piece slots, and only while it is complete.
     */
    public static boolean appliesAt(EnchantmentDefinition definition, Slot slot, Map<String, ItemStack> equipment) {
        if (!definition.isSet()) return true;
        if (definition.set().anchor() != slot) return false;
        for (Slot piece : definition.set().pieces().keySet())
            if (!custom(equipment.get(piece.name())).containsKey(definition.id())) return false;
        return true;
    }

    /**
     * Renders this item's set section for host lore and records it on meta, so equipment changes can redraw
     * it in place. holder is the player whose worn pieces show as equipped, or null for nobody. The host
     * writes the returned lines into the lore of this same meta; they are empty when the item is in no set.
     */
    public static List<String> presentation(ItemMeta meta, Player holder,
                                            Function<String, EnchantmentItems.Resolved> resolver) {
        Map<String, Set<Slot>> worn = holder == null ? Map.of()
                : equippedPieces(EnchantmentActions.captureEquipment(holder), resolver);
        return record(meta, render(meta, worn, resolver, false));
    }

    /**
     * For hosts whose lore is authored text: replaces the first line holding placeholder with the set section,
     * or appends the section when no line holds it, and records it like {@link #presentation}. A placeholder
     * line on an item in no set is removed. The source item is not changed.
     */
    public static ItemStack placePresentation(ItemStack source, String placeholder,
                                              Function<String, EnchantmentItems.Resolved> resolver) {
        ItemStack item = source.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        int at = -1;
        for (int index = 0; index < lore.size() && at < 0; index++) if (lore.get(index).contains(placeholder)) at = index;
        List<String> lines = record(meta, render(meta, Map.of(), resolver, false));
        if (lines.isEmpty() && at < 0) {
            item.setItemMeta(meta);
            return item;
        }
        if (at >= 0) {
            lore.remove(at);
            EnchantmentPresentation.shift(meta, at + 1, lines.size() - 1);
        } else at = lore.size();
        lore.addAll(at, lines);
        meta.setLore(lore.isEmpty() ? null : lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Whether the shared enchantment section shows a line for this item. Sets show in their own section instead. */
    public static boolean hasEnchantmentLines(ItemMeta meta, Function<String, EnchantmentItems.Resolved> resolver) {
        for (String id : custom(meta).keySet()) {
            EnchantmentItems.Resolved resolved;
            try { resolved = resolver.apply(id); }
            catch (RuntimeException unavailable) { return true; }
            if (resolved == null || !resolved.definition().isSet()) return true;
        }
        return false;
    }

    /** Redraws worn-state set lore on every item in this player's inventory. Any host may call it. */
    public static void refresh(Player player) {
        EnchantmentProviders.requireServerThread();
        var inventory = player.getInventory();
        Map<String, Set<Slot>> worn = null;
        for (int index = 0; index <= 40; index++) {
            ItemStack item = inventory.getItem(index);
            if (item == null || item.getType().isAir() || !item.hasItemMeta()) continue;
            ItemMeta meta = item.getItemMeta();
            if (!meta.getPersistentDataContainer().has(PRESENTATION)) continue;
            if (worn == null) worn = equippedPieces(EnchantmentActions.captureEquipment(player), EnchantmentDefinitions::resolve);
            try {
                // The inventory returns the live stack; keep its identity for actions that watch it.
                if (redraw(meta, worn, EnchantmentDefinitions::resolve)) item.setItemMeta(meta);
            } catch (IllegalArgumentException malformed) {
                // Another writer changed the record; the host's next lore rebuild replaces it.
            }
        }
    }

    /** Removes the recorded section so items that differ only in worn-state lore compare as the same item. */
    static void strip(ItemStack item) {
        if (!item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        List<String> stored;
        try { stored = stored(meta); }
        catch (IllegalArgumentException malformed) { return; }
        if (stored == null) return;
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        int start = locate(lore, stored);
        if (start >= 0) {
            lore.subList(start, start + stored.size()).clear();
            EnchantmentPresentation.shift(meta, start + stored.size(), -stored.size());
            meta.setLore(lore.isEmpty() ? null : lore);
        }
        meta.getPersistentDataContainer().remove(PRESENTATION);
        item.setItemMeta(meta);
    }

    static boolean redraw(ItemMeta meta, Map<String, Set<Slot>> worn, Function<String, EnchantmentItems.Resolved> resolver) {
        List<String> stored = stored(meta);
        if (stored == null || !meta.hasLore()) return false;
        List<String> lore = new ArrayList<>(meta.getLore());
        int start = locate(lore, stored);
        if (start < 0) return false;
        List<String> lines = render(meta, worn, resolver, true);
        if (lines == null) return false;
        List<String> normalized = normalize(meta, lines);
        if (normalized.equals(stored)) return false;
        lore.subList(start, start + stored.size()).clear();
        lore.addAll(start, normalized);
        EnchantmentPresentation.shift(meta, start + stored.size(), normalized.size() - stored.size());
        meta.setLore(lore.isEmpty() ? null : lore);
        record(meta, normalized);
        return true;
    }

    /** Null in strict mode when a provider is unavailable, so a redraw never erases lore it cannot rebuild. */
    private static List<String> render(ItemMeta meta, Map<String, Set<Slot>> worn,
                                       Function<String, EnchantmentItems.Resolved> resolver, boolean strict) {
        List<String> lines = new ArrayList<>();
        for (String id : new TreeMap<>(custom(meta)).keySet()) {
            var resolved = resolve(resolver, id);
            if (resolved == null) {
                if (strict) return null;
                continue;
            }
            var definition = resolved.definition();
            if (!definition.isSet()) continue;
            var set = definition.set();
            Set<Slot> filled = worn.getOrDefault(id, Set.of());
            int equipped = (int) set.pieces().keySet().stream().filter(filled::contains).count();
            boolean complete = equipped == set.pieces().size();
            String name = set.name().isEmpty() ? definition.name() : set.name();
            for (String template : set.lore()) {
                String line = template;
                if (line.contains("$ifSetComplete")) {
                    if (!complete) continue;
                    line = line.replace("$ifSetComplete", "");
                }
                if (line.contains("$ifSetIncomplete")) {
                    if (complete) continue;
                    line = line.replace("$ifSetIncomplete", "");
                }
                line = line.replace("$setName", name).replace("$equippedPieces", Integer.toString(equipped))
                        .replace("$totalPieces", Integer.toString(set.pieces().size()));
                if (!line.contains("$setPieces")) {
                    lines.add(ChatColorConverter.convert(line));
                    continue;
                }
                for (var piece : set.pieces().entrySet()) {
                    String format = filled.contains(piece.getKey()) ? set.equippedPiece() : set.missingPiece();
                    lines.add(ChatColorConverter.convert(line.replace("$setPieces", format)
                            .replace("$pieceName", piece.getValue()).replace("$pieceSlot", slotName(piece.getKey()))));
                }
            }
        }
        return lines;
    }

    private static List<String> record(ItemMeta meta, List<String> lines) {
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (lines.isEmpty()) {
            data.remove(PRESENTATION);
            return List.of();
        }
        List<String> normalized = normalize(meta, lines);
        PersistentDataContainer record = data.getAdapterContext().newPersistentDataContainer();
        record.set(EnchantmentItemData.VERSION, PersistentDataType.INTEGER, 1);
        record.set(LINES, PersistentDataType.LIST.strings(), normalized);
        data.set(PRESENTATION, PersistentDataType.TAG_CONTAINER, record);
        return normalized;
    }

    /** Bukkit rewrites legacy formatting when it stores lore; compare against that stored form. */
    private static List<String> normalize(ItemMeta meta, List<String> lines) {
        if (lines.isEmpty()) return List.of();
        ItemMeta scratch = meta.clone();
        scratch.setLore(lines);
        return List.copyOf(scratch.getLore());
    }

    private static List<String> stored(ItemMeta meta) {
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (!data.has(PRESENTATION)) return null;
        PersistentDataContainer record = data.get(PRESENTATION, PersistentDataType.TAG_CONTAINER);
        if (record == null || !Integer.valueOf(1).equals(record.get(EnchantmentItemData.VERSION, PersistentDataType.INTEGER)))
            throw new IllegalArgumentException("Unknown item set presentation record");
        List<String> lines = record.get(LINES, PersistentDataType.LIST.strings());
        return lines == null || lines.isEmpty() ? null : lines;
    }

    /** The section's start, or -1 when the host lore no longer holds exactly one copy of it. */
    private static int locate(List<String> lore, List<String> section) {
        int found = -1;
        for (int start = 0; start + section.size() <= lore.size(); start++) {
            if (!lore.subList(start, start + section.size()).equals(section)) continue;
            if (found >= 0) return -1;
            found = start;
        }
        return found;
    }

    private static Map<String, Integer> custom(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Map.of();
        return custom(item.getItemMeta());
    }

    private static Map<String, Integer> custom(ItemMeta meta) {
        try { return EnchantmentItemData.read(meta); }
        catch (IllegalArgumentException corrupt) { return Map.of(); }
    }

    private static EnchantmentItems.Resolved resolve(Function<String, EnchantmentItems.Resolved> resolver, String id) {
        try {
            var resolved = resolver.apply(id);
            return resolved != null && resolved.available() ? resolved : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static String slotName(Slot slot) {
        return switch (slot) {
            case MAINHAND -> "Main Hand";
            case OFFHAND -> "Off Hand";
            default -> slot.name().charAt(0) + slot.name().substring(1).toLowerCase(Locale.ROOT);
        };
    }
}
