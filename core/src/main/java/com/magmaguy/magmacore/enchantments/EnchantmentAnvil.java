package com.magmaguy.magmacore.enchantments;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.server.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.*;
import java.util.*;
import java.util.function.*;

/** One elected inventory listener across shaded consumers. Hosts supply only item classification and veto policy. */
public final class EnchantmentAnvil {
    private EnchantmentAnvil() { }

    public static Registration register(Plugin owner, Function<ItemStack, EnchantmentItemProfile> classifier,
                                        Function<ItemStack,String> veto) {
        EnchantmentProviders.requireServerThread();
        return new Registration(owner, Objects.requireNonNull(classifier), Objects.requireNonNull(veto));
    }

    public static final class Registration implements AutoCloseable, Listener {
        private final EnchantmentAnvilEndpoint endpoint;
        private AnvilListener listener;
        private boolean closed;
        private Registration(Plugin owner, Function<ItemStack,EnchantmentItemProfile> classifier, Function<ItemStack,String> veto) {
            if (!owner.isEnabled()) throw new IllegalStateException("Anvil provider is disabled");
            if (peers().stream().anyMatch(peer -> peer.owner.equals(owner.getName())))
                throw new IllegalStateException("Anvil policy already registered for " + owner.getName());
            endpoint = new EnchantmentAnvilEndpoint(owner, classifier, veto);
            Bukkit.getPluginManager().registerEvents(this, owner);
            Bukkit.getServicesManager().register(BiFunction.class, endpoint, owner, ServicePriority.Normal);
            reconcile();
        }
        @EventHandler public void registered(ServiceRegisterEvent event) { if (event.getProvider().getService() == BiFunction.class) reconcile(); }
        @EventHandler public void unregistered(ServiceUnregisterEvent event) { if (event.getProvider().getService() == BiFunction.class) reconcile(); }
        @EventHandler public void disabled(PluginDisableEvent event) { if (event.getPlugin() == endpoint.owner) close(); else reconcile(); }
        private void reconcile() {
            if (closed) return;
            if (listener != null) listener.invalidate();
            var peers = peers();
            boolean elected = !peers.isEmpty() && peers.getFirst().endpoint == endpoint;
            if (!elected && listener != null) { HandlerList.unregisterAll(listener); listener = null; }
            if (elected && listener == null) {
                listener = new AnvilListener();
                Bukkit.getPluginManager().registerEvents(listener, endpoint.owner);
            }
        }
        @Override public void close() {
            EnchantmentProviders.requireServerThread();
            if (closed) return;
            closed = true;
            endpoint.active = false;
            if (listener != null) { listener.invalidate(); HandlerList.unregisterAll(listener); listener = null; }
            HandlerList.unregisterAll(this);
            Bukkit.getServicesManager().unregister(BiFunction.class, endpoint);
        }
    }

    private record Peer(String owner, BiFunction<String,ItemStack,Map<String,Object>> endpoint) { }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Peer> peers() {
        var peers = new ArrayList<Peer>();
        for (RegisteredServiceProvider<BiFunction> service : Bukkit.getServicesManager().getRegistrations(BiFunction.class)) {
            if (!service.getPlugin().isEnabled() || !service.getProvider().getClass().getSimpleName().equals("EnchantmentAnvilEndpoint")) continue;
            var endpoint = (BiFunction<String,ItemStack,Map<String,Object>>) service.getProvider();
            var description = endpoint.apply("describe", null);
            if (EnchantmentAnvilEndpoint.PROTOCOL.equals(description.get("protocol"))
                    && service.getPlugin().getName().equals(description.get("owner")))
                peers.add(new Peer(service.getPlugin().getName(), endpoint));
        }
        peers.sort(Comparator.comparing(Peer::owner));
        return peers;
    }

    private static EnchantmentItemProfile profile(ItemStack item) {
        return profile(item, true);
    }

    static EnchantmentItemProfile profile(ItemStack item, boolean anvilPolicy) {
        EnchantmentItemProfile selected = null;
        for (var peer : peers()) {
            Map<String,Object> result = peer.endpoint.apply(anvilPolicy ? "policy" : "classify", item.clone());
            if (result.get("denied") instanceof String reason) throw new IllegalArgumentException(reason);
            if (!Boolean.TRUE.equals(result.get("accepted"))) throw new IllegalStateException("Anvil policy unavailable: " + peer.owner);
            if (result.get("type") instanceof String type) {
                var slots = new HashSet<EnchantmentDefinition.Slot>();
                for (Object slot : (List<?>) result.get("slots")) slots.add(EnchantmentDefinition.Slot.valueOf((String) slot));
                var attacks = new HashSet<String>();
                for (Object attack : (List<?>) result.get("attacks")) attacks.add((String) attack);
                var candidate = new EnchantmentItemProfile(EnchantmentDefinition.ItemType.valueOf(type), slots, attacks);
                if (selected != null && !selected.equals(candidate)) throw new IllegalArgumentException("Conflicting authored item classification");
                selected = candidate;
            }
        }
        return selected == null ? EnchantmentItemProfile.vanilla(item) : selected;
    }

    private static final class AnvilListener implements Listener {
        private final EnchantmentItems items = new EnchantmentItems(EnchantmentDefinitions::resolve, EnchantmentAnvil::profile);
        private final EnchantmentAnvilMerge merge = new EnchantmentAnvilMerge(items);
        private final Map<Inventory, Prepared> prepared = new IdentityHashMap<>();
        private final Map<Inventory, String> denied = new IdentityHashMap<>();
        private final Set<Inventory> committing = Collections.newSetFromMap(new IdentityHashMap<>());
        private record Prepared(EnchantmentAnvilMerge.Preview merge, ItemStack shown, String rename, int cost) { }

        void invalidate() {
            for (var inventory : new ArrayList<>(prepared.keySet())) inventory.setItem(2, null);
            prepared.clear(); denied.clear();
        }
        @EventHandler(priority = EventPriority.HIGHEST)
        public void prepare(PrepareAnvilEvent event) {
            Inventory inventory = event.getInventory();
            if (committing.contains(inventory)) { event.setResult(null); return; }
            prepared.remove(inventory); denied.remove(inventory);
            ItemStack source = inventory.getItem(0), book = inventory.getItem(1);
            if (source == null || book == null || book.getType() != Material.ENCHANTED_BOOK) return;
            try {
                if (!hasCustom(source) && !hasCustom(book)) return;
                profile(source); profile(book);
                AnvilView view = event.getView();
                ItemStack nativeResult = event.getResult();
                int baseCost;
                if (nativeResult != null && !nativeResult.getType().isAir()) baseCost = view.getRepairCost();
                else {
                    baseCost = Math.addExact(priorWork(source), priorWork(book));
                    String oldName = source.getItemMeta().hasDisplayName() ? source.getItemMeta().getDisplayName() : "";
                    String rename = Objects.requireNonNullElse(view.getRenameText(), "");
                    if (!oldName.equals(rename)) baseCost = Math.addExact(baseCost, 1);
                    nativeResult = null;
                }
                var candidate = merge.prepare(source, book, nativeResult, baseCost);
                ItemStack shown = candidate.item();
                if (nativeResult == null) {
                    var meta = shown.getItemMeta();
                    String rename = view.getRenameText();
                    meta.setDisplayName(rename == null || rename.isBlank() ? null : rename);
                    if (meta instanceof Repairable repair)
                        repair.setRepairCost(Math.addExact(Math.multiplyExact(Math.max(priorWork(source), priorWork(book)), 2), 1));
                    shown.setItemMeta(meta);
                }
                view.setRepairCost(candidate.cost());
                boolean creative = event.getView().getPlayer().getGameMode() == GameMode.CREATIVE;
                if (!creative && candidate.cost() >= view.getMaximumRepairCost()) throw new IllegalArgumentException("Enchantment cost exceeds the anvil limit");
                prepared.put(inventory, new Prepared(candidate, shown.clone(), view.getRenameText(), candidate.cost()));
                event.setResult(shown);
            } catch (RuntimeException invalid) {
                denied.put(inventory, Objects.requireNonNullElse(invalid.getMessage(), "Invalid enchantment combination"));
                event.setResult(null);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void pickup(InventoryClickEvent event) {
            if (!(event.getView() instanceof AnvilView view) || event.getRawSlot() != 2) return;
            Inventory inventory = view.getTopInventory();
            Prepared attempt = prepared.get(inventory);
            if (attempt == null && !denied.containsKey(inventory)) return;
            event.setCancelled(true);
            if (attempt == null) { event.getWhoClicked().sendMessage("§c" + denied.get(inventory)); return; }
            if (!(event.getWhoClicked() instanceof Player player) || !committing.add(inventory)) return;
            try {
                if (!Objects.equals(attempt.rename, view.getRenameText()) || attempt.cost != view.getRepairCost()
                        || !attempt.shown.equals(inventory.getItem(2))) throw new ConcurrentModificationException("Anvil result changed");
                ItemStack source = inventory.getItem(0), book = inventory.getItem(1);
                profile(source); profile(book);
                attempt.merge.apply(source, book);
                boolean creative = player.getGameMode() == GameMode.CREATIVE;
                if (!creative && (attempt.cost >= view.getMaximumRepairCost() || player.getLevel() < attempt.cost)) return;
                boolean shift = event.isShiftClick();
                if (!shift && event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
                ItemStack[] storage = null;
                ItemStack cursor = event.getCursor();
                if (shift) {
                    storage = planStorage(player.getInventory(), attempt.shown);
                    if (storage == null) return;
                } else if (cursor != null && !cursor.getType().isAir()
                        && (!cursor.isSimilar(attempt.shown) || cursor.getAmount() + attempt.shown.getAmount() > cursor.getMaxStackSize())) return;
                prepared.remove(inventory);
                inventory.setItem(2, null);
                inventory.setItem(0, consumeOne(source));
                inventory.setItem(1, consumeOne(book));
                if (!creative) player.giveExpLevels(-attempt.cost);
                if (shift) player.getInventory().setStorageContents(storage);
                else {
                    var result = attempt.shown.clone();
                    if (cursor != null && !cursor.getType().isAir()) result.setAmount(result.getAmount() + cursor.getAmount());
                    player.setItemOnCursor(result);
                }
            } catch (RuntimeException invalid) {
                prepared.remove(inventory);
                inventory.setItem(2, null);
                player.sendMessage("§cThe enchantment result changed; place the book again.");
            } finally { committing.remove(inventory); }
        }
        @EventHandler public void closed(InventoryCloseEvent event) { prepared.remove(event.getInventory()); denied.remove(event.getInventory()); }
        private boolean hasCustom(ItemStack item) { return items.inspect(item).keySet().stream().anyMatch(id -> !id.startsWith("minecraft:")); }
    }

    private static int priorWork(ItemStack item) {
        int cost = item.getItemMeta() instanceof Repairable repair ? repair.getRepairCost() : 0;
        if (cost < 0) throw new IllegalArgumentException("Invalid prior-work cost");
        return cost;
    }
    private static ItemStack consumeOne(ItemStack item) {
        if (item.getAmount() == 1) return null;
        var remainder = item.clone(); remainder.setAmount(item.getAmount() - 1); return remainder;
    }
    private static ItemStack[] planStorage(PlayerInventory inventory, ItemStack item) {
        ItemStack[] slots = inventory.getStorageContents();
        int remaining = item.getAmount();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null || slots[i].getType().isAir()) continue;
            slots[i] = slots[i].clone();
            if (!slots[i].isSimilar(item)) continue;
            int transferred = Math.min(remaining, Math.max(0, Math.min(inventory.getMaxStackSize(), slots[i].getMaxStackSize()) - slots[i].getAmount()));
            slots[i].setAmount(slots[i].getAmount() + transferred); remaining -= transferred;
        }
        for (int i = 0; i < slots.length && remaining > 0; i++) {
            if (slots[i] != null && !slots[i].getType().isAir()) continue;
            slots[i] = item.clone();
            int transferred = Math.min(remaining, Math.min(inventory.getMaxStackSize(), item.getMaxStackSize()));
            slots[i].setAmount(transferred); remaining -= transferred;
        }
        return remaining == 0 ? slots : null;
    }
}
