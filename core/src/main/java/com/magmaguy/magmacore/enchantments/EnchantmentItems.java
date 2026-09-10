package com.magmaguy.magmacore.enchantments;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Shared item operations. Hosts retain inventory transactions, acquisition policy and effect dispatch. */
public final class EnchantmentItems {
    /** Reuses registered authored-item classifiers without applying anvil-specific acquisition vetoes. */
    public static EnchantmentItemProfile classify(ItemStack item) {
        EnchantmentProviders.requireServerThread();
        return EnchantmentAnvil.profile(item, false);
    }
    private final Function<String, Resolved> resolver;
    private final Function<ItemStack, EnchantmentItemProfile> classifier;

    public record Resolved(EnchantmentDefinition definition, EnchantmentProviders.Provider provider) {
        public Resolved {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(provider, "provider");
            if (!definition.id().startsWith(provider.namespace() + ":") || provider.generation() == null || provider.revision() < 1)
                throw new IllegalArgumentException("Definition/provider ownership mismatch");
        }
        public boolean available() { return provider.compatible() && provider.capabilities().containsAll(definition.requires()); }
    }

    public EnchantmentItems(Function<String, Resolved> resolver, Function<ItemStack, EnchantmentItemProfile> classifier) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    /** Identity comes only from native metadata and the versioned custom record. */
    public Map<String, Integer> inspect(ItemStack item) {
        EnchantmentProviders.requireServerThread();
        ItemMeta meta = requireMeta(item);
        Map<String, Integer> result = new LinkedHashMap<>();
        nativeEnchantments(meta).forEach((enchantment, level) -> result.put(enchantment.getKey().toString(), level));
        EnchantmentItemData.read(meta).forEach((id, level) -> {
            if (result.putIfAbsent(id, level) != null) throw new IllegalArgumentException("Native/custom identity collision: " + id);
        });
        return Map.copyOf(result);
    }

    /** Produces a reviewable clone. proposed is the complete resulting native/custom enchantment set. */
    public Preview preview(ItemStack source, Map<String, Integer> proposed) {
        return preview(source, proposed, false, false);
    }

    /** Read-only access for hosts that own metadata construction and native overflow separately. */
    public static Map<String, Integer> inspectCustom(ItemMeta meta) {
        EnchantmentProviders.requireServerThread();
        return EnchantmentItemData.read(Objects.requireNonNull(meta, "item metadata"));
    }

    /** Authored gear may deliberately carry native enchantments outside their ordinary material set. */
    public Preview previewAuthored(ItemStack source, Map<String, Integer> proposed) {
        return preview(source, proposed, false, true);
    }

    /** Updates the complete custom set while leaving the host's native enchantment data untouched. */
    public Preview previewCustom(ItemStack source, Map<String, Integer> proposedCustom) {
        return preview(source, proposedCustom, true, false);
    }

    private Preview preview(ItemStack source, Map<String, Integer> proposed, boolean preserveNative,
                            boolean allowUnsupportedNativeMaterial) {
        EnchantmentProviders.requireServerThread();
        ItemStack snapshot = source.clone();
        ItemMeta originalMeta = requireMeta(snapshot);
        inspect(snapshot); // Reject corrupt data even when the proposed set would discard it.
        Map<String, Integer> requested = Map.copyOf(proposed);
        Set<String> nativeIds = new java.util.HashSet<>();
        nativeEnchantments(originalMeta).keySet().forEach(enchantment -> nativeIds.add(enchantment.getKey().toString()));
        EnchantmentItemProfile profile = Objects.requireNonNull(classifier.apply(snapshot.clone()), "item profile");
        Map<String, Resolved> pinned = new LinkedHashMap<>();
        // Provider loss must not turn a custom update into accidental removal of an unavailable entry.
        for (String id : EnchantmentItemData.read(originalMeta).keySet()) pin(id, pinned);
        Map<String, Integer> custom = new LinkedHashMap<>();
        Map<Enchantment, Integer> vanilla = new LinkedHashMap<>();
        for (var entry : requested.entrySet()) {
            String id = EnchantmentDefinition.requireId(entry.getKey());
            Integer level = entry.getValue();
            if (level == null || level < 1) throw new IllegalArgumentException("Enchantment levels must be positive integers");
            if (preserveNative && (id.startsWith("minecraft:") || nativeIds.contains(id)))
                throw new IllegalArgumentException("Custom-only updates cannot write a native enchantment: " + id);
            if (id.startsWith("minecraft:")) {
                Enchantment enchantment = Registry.ENCHANTMENT.get(Objects.requireNonNull(NamespacedKey.fromString(id)));
                if (enchantment == null) throw new IllegalArgumentException("Unknown native enchantment: " + id);
                if (level > enchantment.getMaxLevel()) throw new IllegalArgumentException("Native level exceeds its limit: " + id);
                if (!allowUnsupportedNativeMaterial && !isBook(snapshot) && !enchantment.canEnchantItem(snapshot))
                    throw new IllegalArgumentException("Native enchantment does not support this item: " + id);
                vanilla.put(enchantment, level);
            } else {
                pin(id, pinned);
                custom.put(id, level);
            }
        }
        if (custom.size() > EnchantmentItemData.MAX_ENTRIES) throw new IllegalArgumentException("Too many custom enchantments");
        Set<String> resultingIds = new java.util.HashSet<>(requested.keySet());
        if (preserveNative) resultingIds.addAll(nativeIds);
        List<String> problems = validateCustom(profile, custom, resultingIds, pinned, isBook(snapshot));
        if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
        if (!preserveNative) {
            for (Enchantment first : vanilla.keySet()) for (Enchantment second : vanilla.keySet())
                if (first != second && (first.conflictsWith(second) || second.conflictsWith(first)))
                    throw new IllegalArgumentException("Conflicting native enchantments: " + first.getKey() + " / " + second.getKey());
            for (Enchantment existing : nativeEnchantments(originalMeta).keySet())
                if (!existing.getKey().getNamespace().equals("minecraft"))
                    throw new IllegalArgumentException("Updating third-party native registry enchantments is not supported: " + existing.getKey());
        }
        ItemStack draft = snapshot.clone();
        ItemMeta meta = requireMeta(draft);
        if (!preserveNative) {
            if (meta instanceof EnchantmentStorageMeta book) {
                for (Enchantment existing : List.copyOf(book.getStoredEnchants().keySet())) book.removeStoredEnchant(existing);
                for (var entry : vanilla.entrySet())
                    if (!book.addStoredEnchant(entry.getKey(), entry.getValue(), false)) throw new IllegalArgumentException("Native book enchantment rejected");
            } else {
                meta.removeEnchantments();
                for (var entry : vanilla.entrySet())
                    if (!meta.addEnchant(entry.getKey(), entry.getValue(), false)) throw new IllegalArgumentException("Native enchantment rejected");
            }
        }
        EnchantmentItemData.write(meta, custom);
        EnchantmentPresentation.render(meta, custom, pinned::get);
        if (!draft.setItemMeta(meta)) throw new IllegalArgumentException("Item rejected its metadata");
        requireCurrent(pinned);
        return new Preview(snapshot, draft, profile, Map.copyOf(pinned));
    }

    /** Redraws known/inactive entries without changing their identities or levels. */
    public ItemStack refreshPresentation(ItemStack source) {
        return refreshPresentation(source, UnaryOperator.identity());
    }

    /**
     * Rebuilds host lore beneath the validated shared prefix. The synchronous callback receives
     * immutable host-only text and must return non-null lines. The source item is never mutated.
     */
    public ItemStack refreshPresentation(ItemStack source, UnaryOperator<List<String>> rebuildHostLore) {
        EnchantmentProviders.requireServerThread();
        Objects.requireNonNull(rebuildHostLore, "host lore builder");
        ItemStack result = source.clone();
        ItemMeta meta = requireMeta(result);
        EnchantmentPresentation.render(meta, EnchantmentItemData.read(meta), resolver, rebuildHostLore);
        if (!result.setItemMeta(meta)) throw new IllegalArgumentException("Item rejected its metadata");
        return result;
    }

    public final class Preview {
        private final ItemStack source, draft;
        private final EnchantmentItemProfile profile;
        private final Map<String, Resolved> pinned;

        private Preview(ItemStack source, ItemStack draft, EnchantmentItemProfile profile, Map<String, Resolved> pinned) {
            this.source = source;
            this.draft = draft;
            this.profile = profile;
            this.pinned = pinned;
        }
        /** For menu display only. Application must pass through apply against the actual current item. */
        public ItemStack previewItem() { EnchantmentProviders.requireServerThread(); return draft.clone(); }
        public ItemStack apply(ItemStack current) {
            EnchantmentProviders.requireServerThread();
            if (!source.equals(current) || !profile.equals(classifier.apply(current.clone())))
                throw new ConcurrentModificationException("The source item or its definition changed after preview");
            requireCurrent(pinned);
            return draft.clone();
        }
    }

    private void pin(String id, Map<String, Resolved> pinned) {
        Resolved current = resolver.apply(id);
        if (current == null || !current.available() || !current.definition().id().equals(id))
            throw new IllegalArgumentException("Unavailable enchantment: " + id);
        Resolved previous = pinned.putIfAbsent(id, current);
        if (previous != null && !previous.equals(current)) throw new ConcurrentModificationException("Provider changed during preview");
    }

    private void requireCurrent(Map<String, Resolved> pinned) {
        for (var entry : pinned.entrySet())
            if (!entry.getValue().equals(resolver.apply(entry.getKey())))
                throw new ConcurrentModificationException("Enchantment provider changed after preview: " + entry.getKey());
    }

    static List<String> validateCustom(EnchantmentItemProfile profile, Map<String, Integer> requested,
                                       Set<String> resultingIds,
                                       Map<String, Resolved> resolved, boolean book) {
        List<String> problems = new ArrayList<>();
        for (var entry : requested.entrySet()) {
            if (entry.getKey().startsWith("minecraft:")) continue;
            Resolved resolution = resolved.get(entry.getKey());
            if (resolution == null || !resolution.available()) { problems.add("Unavailable enchantment: " + entry.getKey()); continue; }
            EnchantmentDefinition definition = resolution.definition();
            if (entry.getValue() < 1 || entry.getValue() > definition.maxLevel()) problems.add("Level outside limits: " + entry.getKey());
            if (!book) {
                if (!definition.itemTypes().isEmpty() && !definition.itemTypes().contains(profile.type()))
                    problems.add("Item type does not support " + entry.getKey());
                if (!definition.validSlots().isEmpty() && java.util.Collections.disjoint(definition.validSlots(), profile.slots()))
                    problems.add("No valid equipment slot for " + entry.getKey());
                if (!definition.attackKinds().isEmpty() && java.util.Collections.disjoint(definition.attackKinds(), profile.attackKinds()))
                    problems.add("No supported attack for " + entry.getKey());
            }
            for (String conflict : definition.conflicts())
                if (resultingIds.contains(conflict)) problems.add("Conflicting enchantments: " + entry.getKey() + " / " + conflict);
        }
        return List.copyOf(problems);
    }

    private static Map<Enchantment, Integer> nativeEnchantments(ItemMeta meta) {
        if (meta instanceof EnchantmentStorageMeta book) {
            if (!meta.getEnchants().isEmpty()) throw new IllegalArgumentException("Book has conflicting native storage forms");
            return book.getStoredEnchants();
        }
        return meta.getEnchants();
    }
    private static boolean isBook(ItemStack item) { return item.getType() == Material.ENCHANTED_BOOK; }
    private static ItemMeta requireMeta(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() < 1) throw new IllegalArgumentException("Expected a nonempty item");
        return Objects.requireNonNull(item.getItemMeta(), "item metadata");
    }
}
