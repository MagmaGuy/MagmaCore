package com.magmaguy.magmacore.enchantments;

import org.bukkit.inventory.ItemStack;
import java.util.Set;
import static com.magmaguy.magmacore.enchantments.EnchantmentDefinition.ItemType;
import static com.magmaguy.magmacore.enchantments.EnchantmentDefinition.Slot;

/** Hosts supply canonical modeled-item families; ordinary equipment uses this shared classifier. */
public record EnchantmentItemProfile(ItemType type, Set<Slot> slots, Set<String> attackKinds) {
    public EnchantmentItemProfile {
        java.util.Objects.requireNonNull(type, "type");
        slots = Set.copyOf(slots);
        attackKinds = Set.copyOf(attackKinds);
        if (!EnchantmentDefinition.ATTACK_KINDS.containsAll(attackKinds)) throw new IllegalArgumentException("Unknown attack kind");
    }

    public static EnchantmentItemProfile vanilla(ItemStack item) {
        String material = item.getType().name();
        ItemType type = ItemType.OTHER;
        for (ItemType candidate : ItemType.values()) {
            if (candidate == ItemType.WAND || candidate == ItemType.STAFF || candidate == ItemType.OTHER) continue;
            if (material.equals(candidate.name()) || material.endsWith("_" + candidate.name())) {
                type = candidate;
                break;
            }
        }
        Set<Slot> slots = switch (type) {
            case HELMET -> Set.of(Slot.HEAD);
            case CHESTPLATE, ELYTRA -> Set.of(Slot.CHEST);
            case LEGGINGS -> Set.of(Slot.LEGS);
            case BOOTS -> Set.of(Slot.FEET);
            default -> Set.of(Slot.MAINHAND, Slot.OFFHAND);
        };
        return new EnchantmentItemProfile(type, slots, Set.of());
    }
}
