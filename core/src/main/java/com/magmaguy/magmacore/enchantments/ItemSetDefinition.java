package com.magmaguy.magmacore.enchantments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an enchantment into an item set. Every piece slot must hold an item carrying the enchantment
 * before any of its hooks run, and then they run once per input instead of once per piece.
 * Lore templates use the same $placeholder style as host item lore; see {@link ItemSets}.
 */
public record ItemSetDefinition(String name, Map<EnchantmentDefinition.Slot, String> pieces, List<String> lore,
                                String equippedPiece, String missingPiece) {
    public static final List<String> DEFAULT_LORE = List.of("&6$setName &7($equippedPieces/$totalPieces)", "$setPieces");
    public static final String DEFAULT_EQUIPPED_PIECE = "&a✔ $pieceName";
    public static final String DEFAULT_MISSING_PIECE = "&c✘ $pieceName";
    private static final int MAX_LINE = 512;

    public ItemSetDefinition {
        name = name == null ? "" : requireLine(name, "set name");
        if (pieces == null || pieces.isEmpty()) throw new IllegalArgumentException("A set needs at least one piece");
        EnumMap<EnchantmentDefinition.Slot, String> ordered = new EnumMap<>(EnchantmentDefinition.Slot.class);
        pieces.forEach((slot, piece) -> {
            java.util.Objects.requireNonNull(slot, "piece slot");
            if (piece == null || piece.isBlank()) throw new IllegalArgumentException("Set pieces need a name: " + slot);
            ordered.put(slot, requireLine(piece, "piece name"));
        });
        pieces = Collections.unmodifiableMap(ordered);
        if (lore == null) lore = DEFAULT_LORE;
        if (lore.size() > 64) throw new IllegalArgumentException("Set lore is limited to 64 lines");
        List<String> lines = new ArrayList<>();
        for (String line : lore) lines.add(requireLine(line, "set lore line"));
        lore = List.copyOf(lines);
        equippedPiece = equippedPiece == null ? DEFAULT_EQUIPPED_PIECE : requireLine(equippedPiece, "equippedPiece");
        missingPiece = missingPiece == null ? DEFAULT_MISSING_PIECE : requireLine(missingPiece, "missingPiece");
    }

    /** Sets run their hooks with the first piece in slot order as their source item. */
    public EnchantmentDefinition.Slot anchor() { return pieces.keySet().iterator().next(); }

    private static String requireLine(String value, String field) {
        if (value == null || value.length() > MAX_LINE || value.indexOf('§') >= 0
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(field + " must be text of at most " + MAX_LINE + " characters using & color codes");
        return value;
    }
}
