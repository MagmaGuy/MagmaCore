package com.magmaguy.magmacore.enchantments;

import com.magmaguy.magmacore.scripting.ScriptHook;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import java.util.*;

/** Accepted Bukkit inputs share one elected provider. Plugin-owned attacks supply their own captured source. */
public final class EnchantmentInputs implements Listener {
    public static final ScriptHook ATTACK = new ScriptHook("on_attack_entity");
    public static final ScriptHook PROJECTILE_HIT = new ScriptHook("on_projectile_hit");
    public static final ScriptHook TAKE_DAMAGE = new ScriptHook("on_take_damage");
    public static final ScriptHook RIGHT_CLICK = new ScriptHook("on_right_click");
    public static final ScriptHook LEFT_CLICK = new ScriptHook("on_left_click");
    public static final ScriptHook SHIFT_RIGHT_CLICK = new ScriptHook("on_shift_right_click");
    public static final ScriptHook SHIFT_LEFT_CLICK = new ScriptHook("on_shift_left_click");
    public static final ScriptHook BREAK_BLOCK = new ScriptHook("on_break_block");
    public static final Set<ScriptHook> HOOKS = Set.of(ATTACK, PROJECTILE_HIT, TAKE_DAMAGE,
            RIGHT_CLICK, LEFT_CLICK, SHIFT_RIGHT_CLICK, SHIFT_LEFT_CLICK, BREAK_BLOCK,
            ScriptHook.ON_TICK, ScriptHook.ON_ZONE_ENTER, ScriptHook.ON_ZONE_LEAVE);
    private static final String SHOT = "nightbreak_enchantment_shot";
    private static final String EXPLICIT_DAMAGE = "nightbreak_enchantment_explicit_damage";
    private static final String OWNED_DAMAGE_EVENTS = "nightbreak_enchantment_owned_damage_events";
    private static final String DAMAGE_OBSERVER = "nightbreak_enchantment_damage_observer";
    private final Plugin plugin;
    private final String namespace;
    private final Set<UUID> warned = new HashSet<>();

    EnchantmentInputs(Plugin plugin, String namespace) { this.plugin = plugin; this.namespace = namespace; }
    void clearWarnings() { warned.clear(); }

    static boolean isInteraction(ScriptHook hook) {
        return hook.equals(RIGHT_CLICK) || hook.equals(LEFT_CLICK)
                || hook.equals(SHIFT_RIGHT_CLICK) || hook.equals(SHIFT_LEFT_CLICK);
    }

    public static Map<String,Object> copySnapshot(Map<String,Object> snapshot) { return EnchantmentValues.copy(snapshot); }

    /** Marks only this programmatic event, including nested events, across shaded input owners. */
    public static void markExplicitDamage(Plugin owner, EntityDamageByEntityEvent event) {
        EnchantmentProviders.requireServerThread();
        Map<Object, Boolean> events = null;
        for (var value : event.getDamager().getMetadata(OWNED_DAMAGE_EVENTS)) {
            if (value.getOwningPlugin() == owner && value.value() instanceof Map<?, ?> existing) {
                @SuppressWarnings("unchecked") Map<Object, Boolean> owned = (Map<Object, Boolean>) existing;
                events = owned;
                break;
            }
        }
        if (events == null) {
            events = new WeakHashMap<>();
            event.getDamager().setMetadata(OWNED_DAMAGE_EVENTS, new FixedMetadataValue(owner, events));
        }
        events.put(event, Boolean.TRUE);
    }

    private static boolean isExplicitDamage(EntityDamageByEntityEvent event) {
        return event.getDamager().getMetadata(OWNED_DAMAGE_EVENTS).stream()
                .anyMatch(value -> value.value() instanceof Map<?, ?> events && events.containsKey(event));
    }

    /** Uses the elected native input observer; a cancelled or absent hit is not an applied effect. */
    public static boolean applyExplicitDamage(Plugin owner, Player actor, LivingEntity target, Runnable damage) {
        EnchantmentProviders.requireServerThread();
        var existing = actor.getMetadata(DAMAGE_OBSERVER).stream()
                .filter(value -> value.value() instanceof Deque<?>).findFirst().orElse(null);
        @SuppressWarnings("unchecked")
        Deque<java.util.function.Consumer<EntityDamageByEntityEvent>> observers = existing == null ? new ArrayDeque<>()
                : (Deque<java.util.function.Consumer<EntityDamageByEntityEvent>>) existing.value();
        Plugin registrationOwner = existing == null ? owner : existing.getOwningPlugin();
        boolean[] observed = {false}, accepted = {false};
        java.util.function.Consumer<EntityDamageByEntityEvent> observer = event -> {
            if (observed[0] || event.getEntity() != target || event.getDamager() != actor) return;
            observed[0] = true;
            accepted[0] = !event.isCancelled() && event.getFinalDamage() > 0;
        };
        if (existing == null) actor.setMetadata(DAMAGE_OBSERVER, new FixedMetadataValue(owner, observers));
        observers.addLast(observer);
        try { runExplicitDamage(owner, actor, damage); return accepted[0]; }
        finally {
            observers.removeLastOccurrence(observer);
            if (observers.isEmpty()) actor.removeMetadata(DAMAGE_OBSERVER, registrationOwner);
        }
    }

    private boolean elected() {
        return EnchantmentProviders.providers().stream().filter(provider -> provider.compatible()
                        && provider.capabilities().contains(EnchantmentActions.CAPABILITY))
                .findFirst().map(provider -> provider.namespace().equals(namespace)
                        && provider.plugin().equals(plugin.getName())).orElse(false);
    }

    /** The snapshot carries Bukkit/JDK values only, including pinned definition revisions and consumed ammunition. */
    public static Map<String, Object> capture(Player actor, ItemStack item, EnchantmentDefinition.Slot slot,
            int inventorySlot, ItemStack ammunition, String attackKind, UUID attackId) {
        EnchantmentProviders.requireServerThread();
        if (item == null || item.getType().isAir() || item.getType() == org.bukkit.Material.ENCHANTED_BOOK
                || !item.hasItemMeta()) return Map.of();
        var levels = EnchantmentItemData.read(item.getItemMeta());
        if (levels.isEmpty()) return Map.of();
        EnchantmentItemProfile profile = EnchantmentItems.classify(item);
        List<Map<String,Object>> effects = new ArrayList<>();
        for (var entry : new TreeMap<>(levels).entrySet()) {
            var resolved = EnchantmentDefinitions.resolve(entry.getKey());
            if (resolved == null || !resolved.available()) throw new IllegalArgumentException("Unavailable enchantment " + entry.getKey());
            var definition = resolved.definition();
            if (entry.getValue() < 1 || entry.getValue() > definition.maxLevel())
                throw new IllegalArgumentException("Invalid level for " + entry.getKey());
            if (!definition.validSlots().isEmpty() && !definition.validSlots().contains(slot)) continue;
            if (!definition.itemTypes().isEmpty() && !definition.itemTypes().contains(profile.type())) continue;
            if (!definition.attackKinds().isEmpty() && !definition.attackKinds().contains(attackKind)) continue;
            if (!Collections.disjoint(definition.conflicts(), levels.keySet()))
                throw new IllegalArgumentException("Conflicting enchantments on source item");
            if (!resolved.provider().capabilities().contains(EnchantmentActions.CAPABILITY)) continue;
            effects.add(Map.of("id", definition.id(), "level", entry.getValue(),
                    "generation", resolved.provider().generation(), "revision", resolved.provider().revision()));
        }
        if (effects.isEmpty()) return Map.of();
        Map<String,Object> facts = new LinkedHashMap<>();
        facts.put("item", item.clone()); facts.put("inventory_slot", inventorySlot); facts.put("slot", slot.name());
        facts.put("equipment", EnchantmentActions.captureEquipment(actor)); facts.put("attack_kind", attackKind);
        if (ammunition != null && !ammunition.getType().isAir()) facts.put("ammunition", ammunition.clone());
        return EnchantmentValues.copy(Map.of("attack", attackId, "actor", actor.getUniqueId(),
                "world", actor.getWorld().getUID(), "facts", facts, "effects", effects));
    }

    /** Called after the base action succeeds. A stale or failed optional effect cannot replay the base action. */
    public static boolean dispatch(Plugin owner, Map<String,Object> captured, ScriptHook hook, LivingEntity target, String stage) {
        EnchantmentProviders.requireServerThread();
        if (captured.isEmpty()) return false;
        boolean cancelled = false;
        Map<String,Object> snapshot = EnchantmentValues.copy(captured);
        for (Object raw : (List<?>) snapshot.get("effects")) {
            Map<?,?> effect = (Map<?,?>) raw;
            String id = (String) effect.get("id");
            try {
                var resolved = EnchantmentDefinitions.resolve(id);
                if (resolved == null || !resolved.available() || !resolved.provider().generation().equals(effect.get("generation"))
                        || !Long.valueOf(resolved.provider().revision()).equals(effect.get("revision"))) continue;
                int level = (Integer) effect.get("level");
                Object authoredLifetime = resolved.definition().parametersAt(level).get("lifetime_ticks");
                // Numeric-only definitions never need an activation. A one-tick probe is rejected by
                // the provider before allocation when the requested action hook is absent.
                long lifetime = authoredLifetime == null ? 1 : lifetime(authoredLifetime);
                var source = new EnchantmentActions.Source((UUID) snapshot.get("attack"), (UUID) snapshot.get("actor"),
                        (UUID) snapshot.get("world"), lifetime, EnchantmentValues.copy((Map<?,?>) snapshot.get("facts")));
                var action = EnchantmentActions.begin(resolved, level, source, hook);
                if (action != null) {
                    action.dispatchFinal(stage, hook, target == null ? null : target.getUniqueId());
                    cancelled |= action.cancelledInput();
                }
            } catch (RuntimeException failure) {
                owner.getLogger().warning("Optional enchantment " + id + " failed for attack "
                        + snapshot.get("attack") + ": " + failure.getMessage());
            }
        }
        return cancelled;
    }

    private static long lifetime(Object authored) {
        if (!(authored instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < 1 || number.doubleValue() >= Long.MAX_VALUE
                || number.doubleValue() != Math.rint(number.doubleValue()))
            throw new IllegalArgumentException("lifetime_ticks must be a positive whole number");
        return number.longValue();
    }

    /** Suppresses automatic intake while the gameplay owner applies and explicitly attributes a hit. */
    public static void runExplicitDamage(Plugin owner, Player actor, Runnable damage) {
        EnchantmentProviders.requireServerThread();
        var previous = actor.getMetadata(EXPLICIT_DAMAGE).stream().filter(value -> value.getOwningPlugin() == owner).findFirst().orElse(null);
        actor.setMetadata(EXPLICIT_DAMAGE, new FixedMetadataValue(owner, true));
        try { damage.run(); }
        finally {
            actor.removeMetadata(EXPLICIT_DAMAGE, owner);
            if (previous != null) actor.setMetadata(EXPLICIT_DAMAGE, previous);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void shoot(EntityShootBowEvent event) {
        if (!elected() || !(event.getEntity() instanceof Player player) || !(event.getProjectile() instanceof Projectile projectile)) return;
        EquipmentSlot hand = event.getHand();
        try {
            var captured = capture(player, event.getBow(), slot(hand), inventorySlot(player, hand),
                    event.shouldConsumeItem() ? event.getConsumable() : null, "", projectile.getUniqueId());
            projectile.setMetadata(SHOT, new FixedMetadataValue(plugin, captured));
        } catch (RuntimeException invalid) { warn(player, invalid); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void attack(EntityDamageByEntityEvent event) {
        // Model/proxy APIs subclass the Bukkit event for previews and forward a separate native
        // damage call. Only that final call is an automatic proc source; owners can dispatch explicitly.
        if (event.getClass() != EntityDamageByEntityEvent.class) return;
        if (!elected()) return;
        for (var value : event.getDamager().getMetadata(DAMAGE_OBSERVER))
            if (value.value() instanceof Deque<?> observers
                    && observers.peekLast() instanceof java.util.function.Consumer<?> observer) {
                @SuppressWarnings("unchecked") var typed = (java.util.function.Consumer<EntityDamageByEntityEvent>) observer;
                typed.accept(event);
            }
        if (isExplicitDamage(event)) return;
        if (event.isCancelled() || event.getFinalDamage() <= 0 || !(event.getEntity() instanceof LivingEntity target)) return;
        if (event.getDamager() instanceof Player player) {
            if (player.hasMetadata(EXPLICIT_DAMAGE)) return;
            fire(player, player.getInventory().getItemInMainHand(), EnchantmentDefinition.Slot.MAINHAND,
                    player.getInventory().getHeldItemSlot(), ATTACK, target);
        } else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            for (var metadata : projectile.getMetadata(SHOT)) {
                if (metadata.value() instanceof Map<?,?> source && !source.isEmpty()) {
                    // A projectile may pierce several targets, but one accepted target impact is delivered once.
                    String claim = SHOT + "_" + target.getUniqueId();
                    if (projectile.hasMetadata(claim)) return;
                    projectile.setMetadata(claim, new FixedMetadataValue(plugin, true));
                    try { dispatch(plugin, EnchantmentValues.copy(source), PROJECTILE_HIT, target, projectile.getUniqueId() + "/" + target.getUniqueId()); }
                    catch (RuntimeException invalid) { warn(player, invalid); }
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damaged(EntityDamageEvent event) {
        if (event.getClass() != EntityDamageEvent.class && event.getClass() != EntityDamageByEntityEvent.class
                && event.getClass() != EntityDamageByBlockEvent.class) return;
        if (!elected() || event.getFinalDamage() <= 0 || !(event.getEntity() instanceof Player player)) return;
        var inventory = player.getInventory();
        for (var slot : EnchantmentDefinition.Slot.values()) {
            int index = switch (slot) {
                case MAINHAND -> inventory.getHeldItemSlot(); case OFFHAND -> 40;
                case HEAD -> 39; case CHEST -> 38; case LEGS -> 37; case FEET -> 36;
            };
            fire(player, inventory.getItem(index), slot, index, TAKE_DAMAGE, null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        // Bukkit denies the absent block side of ordinary air interactions. Both sides denied
        // is the same explicit protection/cancellation rule used by the existing FMM input owner.
        if (!elected() || event.getHand() == null || (event.useItemInHand() == Event.Result.DENY
                && event.useInteractedBlock() == Event.Result.DENY)) return;
        if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL) return;
        boolean right = event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                || event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK;
        Player player = event.getPlayer();
        ScriptHook hook = right ? (player.isSneaking() ? SHIFT_RIGHT_CLICK : RIGHT_CLICK)
                : (player.isSneaking() ? SHIFT_LEFT_CLICK : LEFT_CLICK);
        if (fire(player, event.getItem(), slot(event.getHand()), inventorySlot(player, event.getHand()), hook, null)) {
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler public void quit(PlayerQuitEvent event) { warned.remove(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void mine(org.bukkit.event.block.BlockBreakEvent event) {
        if (!elected() || event.getClass() != org.bukkit.event.block.BlockBreakEvent.class) return;
        Player actor = event.getPlayer();
        try {
            UUID id = UUID.randomUUID();
            var captured = capture(actor, actor.getInventory().getItemInMainHand(), EnchantmentDefinition.Slot.MAINHAND,
                    actor.getInventory().getHeldItemSlot(), null, "", id);
            if (captured.isEmpty()) return;
            Map<String, Object> source = new LinkedHashMap<>(captured);
            @SuppressWarnings("unchecked") Map<String, Object> originalFacts = (Map<String, Object>) captured.get("facts");
            Map<String, Object> facts = new LinkedHashMap<>(originalFacts);
            var block = event.getBlock();
            facts.put("block", Map.of("x", block.getX(), "y", block.getY(), "z", block.getZ(),
                    "material", block.getType().name().toLowerCase(Locale.ROOT)));
            facts.put("drop_items", event.isDropItems());
            facts.put("sneaking", actor.isSneaking());
            source.put("facts", facts);
            dispatch(plugin, source, BREAK_BLOCK, null, id.toString());
        } catch (RuntimeException invalid) { warn(actor, invalid); }
    }

    private boolean fire(Player actor, ItemStack item, EnchantmentDefinition.Slot slot, int index, ScriptHook hook, LivingEntity target) {
        try {
            UUID id = UUID.randomUUID();
            return dispatch(plugin, capture(actor, item, slot, index, null, "", id), hook, target, id.toString());
        } catch (RuntimeException invalid) { warn(actor, invalid); }
        return false;
    }
    private void warn(Player player, RuntimeException failure) {
        if (warned.add(player.getUniqueId())) plugin.getLogger().warning("Enchantment input rejected for " + player.getName() + ": " + failure.getMessage());
    }
    private static EnchantmentDefinition.Slot slot(EquipmentSlot hand) {
        return hand == EquipmentSlot.HAND ? EnchantmentDefinition.Slot.MAINHAND : EnchantmentDefinition.Slot.OFFHAND;
    }
    private static int inventorySlot(Player player, EquipmentSlot hand) {
        return hand == EquipmentSlot.HAND ? player.getInventory().getHeldItemSlot() : 40;
    }
}
