package com.magmaguy.magmacore.enchantments;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.function.*;

/** JDK service type and cloned Bukkit items avoid sharing relocated MagmaCore classes. */
final class EnchantmentAnvilEndpoint implements BiFunction<String, ItemStack, Map<String,Object>> {
    static final String PROTOCOL = "nightbreak.enchantment-anvil.v1";
    final Plugin owner;
    private final Function<ItemStack, EnchantmentItemProfile> classifier;
    private final Function<ItemStack, String> veto;
    boolean active = true;
    EnchantmentAnvilEndpoint(Plugin owner, Function<ItemStack, EnchantmentItemProfile> classifier, Function<ItemStack,String> veto) {
        this.owner = owner; this.classifier = classifier; this.veto = veto;
    }
    @Override public Map<String,Object> apply(String operation, ItemStack item) {
        EnchantmentProviders.requireServerThread();
        if (!active || !owner.isEnabled()) return Map.of();
        if (operation.equals("describe")) return Map.of("protocol", PROTOCOL, "owner", owner.getName());
        if (!operation.equals("policy") || item == null) throw new IllegalArgumentException("Invalid anvil policy call");
        String denied = veto.apply(item.clone());
        if (denied != null) return Map.of("denied", denied);
        EnchantmentItemProfile profile = classifier.apply(item.clone());
        if (profile == null) return Map.of("accepted", true);
        return Map.of("accepted", true, "type", profile.type().name(),
                "slots", profile.slots().stream().map(Enum::name).sorted().toList(),
                "attacks", profile.attackKinds().stream().sorted().toList());
    }
}
