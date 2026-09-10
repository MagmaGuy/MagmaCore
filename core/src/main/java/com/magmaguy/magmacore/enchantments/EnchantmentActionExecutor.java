package com.magmaguy.magmacore.enchantments;

import com.magmaguy.magmacore.scripting.ScriptHook;
import com.magmaguy.magmacore.scripting.ScriptInstance;
import com.magmaguy.magmacore.scripting.ScriptableEntity;
import com.magmaguy.magmacore.scripting.tables.LuaLivingEntityTable;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.ZeroArgFunction;
import com.magmaguy.magmacore.scripting.tables.LuaTableSupport;
import com.magmaguy.magmacore.util.TemporaryBlockManager;
import com.magmaguy.magmacore.util.OwnedEntityState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Uses ordinary ScriptInstance ownership/watchdogs; there is no separate enchantment tick engine. */
final class EnchantmentActionExecutor implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final Set<ScriptHook> hooks;
    private final Set<String> capabilities;
    private final EnchantmentInputs inputs;
    private final Map<String, Running> active = new HashMap<>();
    // Activations are short lived; cooldowns belong to the player and authored effect.
    // Keep them through action completion, equipment swaps, world changes and valid reloads.
    private final Map<UUID, Map<String, Long>> globalCooldowns = new HashMap<>();
    private final Map<UUID, Map<String, Map<String, Long>>> localCooldowns = new HashMap<>();
    private EnchantmentCatalog catalog;
    private boolean closed;

    EnchantmentActionExecutor(Plugin plugin, EnchantmentCatalog catalog, Set<ScriptHook> hooks,
                              Set<String> capabilities) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.hooks = Set.copyOf(hooks);
        this.capabilities = Set.copyOf(capabilities);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        inputs = new EnchantmentInputs(plugin, catalog.namespace());
        Bukkit.getPluginManager().registerEvents(inputs, plugin);
    }

    Map<String, Object> evaluate(Map<String, Object> request) {
        if (closed) return response(EnchantmentActions.Status.UNAVAILABLE);
        return switch (String.valueOf(request.get("operation"))) {
            case "begin" -> begin(request);
            case "dispatch" -> dispatch(request);
            case "close" -> {
                requireKeys(request, "kind", "operation", "token");
                Running running = active.get(text(request, "token"));
                if (running != null) running.instance.shutdown();
                yield response(EnchantmentActions.Status.OK);
            }
            default -> throw new IllegalArgumentException("Unknown action operation");
        };
    }

    private Map<String, Object> begin(Map<String, Object> request) {
        requireKeys(request, "kind", "operation", "id", "level", "attack", "actor", "world", "lifetime", "facts", "hook");
        String id = text(request, "id");
        if (!(request.get("level") instanceof Integer level) || !(request.get("lifetime") instanceof Long lifetime)
                || lifetime < 1 || !(request.get("facts") instanceof Map<?, ?> facts))
            throw new IllegalArgumentException("Invalid action source");
        var definition = catalog.definitions().get(id);
        var script = catalog.script(id).orElse(null);
        if (definition == null || script == null || level < 1 || level > definition.maxLevel())
            return response(EnchantmentActions.Status.INVALID);
        String initialHook = text(request, "hook");
        if (!initialHook.isEmpty() && !script.supportsHook(new ScriptHook(initialHook)))
            return response(EnchantmentActions.Status.UNAVAILABLE);
        if (!capabilities.containsAll(definition.requires())) return response(EnchantmentActions.Status.UNAVAILABLE);
        var source = new EnchantmentActions.Source(UUID.fromString(text(request, "attack")),
                UUID.fromString(text(request, "actor")), UUID.fromString(text(request, "world")),
                lifetime, EnchantmentValues.copy(facts));
        Running running = new Running(UUID.randomUUID().toString(), definition, level, source);
        if (!running.isScriptOwnerActive()) return response(EnchantmentActions.Status.UNAVAILABLE);
        running.instance = new ScriptInstance(script, running);
        active.put(running.token, running);
        try {
            // Guard all owned work, even scripts that do not declare an on_game_tick hook.
            running.guardTask = running.instance.ownRepeating(1, 1, () -> {
                if (--running.remainingTicks <= 0 || !running.isScriptOwnerActive()
                        || running.finalStage && !running.instance.hasOwnedWorkExcept(running.guardTask))
                    running.instance.shutdown();
            });
        } catch (RuntimeException failure) {
            running.instance.shutdown();
            throw failure;
        }
        return Map.of("status", EnchantmentActions.Status.OK.name(), "token", running.token);
    }

    private Map<String, Object> dispatch(Map<String, Object> request) {
        requireKeys(request, "kind", "operation", "token", "stage", "hook", "target", "final");
        if (!(request.get("final") instanceof Boolean finalStage)) throw new IllegalArgumentException("Invalid final-stage flag");
        Running running = active.get(text(request, "token"));
        if (running == null) return response(EnchantmentActions.Status.UNAVAILABLE);
        if (running.finalStage) return response(EnchantmentActions.Status.UNAVAILABLE);
        if (!running.isScriptOwnerActive()) {
            running.instance.shutdown();
            return response(EnchantmentActions.Status.UNAVAILABLE);
        }
        ScriptHook hook = new ScriptHook(text(request, "hook"));
        if (!hooks.contains(hook)) return response(EnchantmentActions.Status.INVALID);
        String stage = text(request, "stage");
        if (stage.isBlank()) throw new IllegalArgumentException("Missing stage identity");
        String targetId = text(request, "target");
        Entity entity = targetId.isEmpty() ? null : Bukkit.getEntity(UUID.fromString(targetId));
        if (!targetId.isEmpty() && (!(entity instanceof LivingEntity) || !entity.isValid()
                || !entity.getWorld().getUID().equals(running.source.world())))
            return response(EnchantmentActions.Status.UNAVAILABLE);
        // Claim before invoking Lua, including reentrant event dispatch caused by authored damage.
        if (!running.delivered.add(stage + "/" + hook.getKey())) return response(EnchantmentActions.Status.DUPLICATE);
        running.finalStage = finalStage;
        running.target = entity instanceof LivingEntity living ? living : null;
        InputEvent input = EnchantmentInputs.isInteraction(hook) ? new InputEvent() : null;
        running.instance.handleEvent(hook, input, running.target, (LivingEntity) running.getBukkitEntity());
        if (running.finalStage && !running.instance.hasOwnedWorkExcept(running.guardTask)) running.instance.shutdown();
        return Map.of("status", (running.failed ? EnchantmentActions.Status.FAILED : EnchantmentActions.Status.OK).name(),
                "cancelled", input != null && input.isCancelled());
    }

    /** Local event table; only its synchronous cancellation result crosses the provider boundary. */
    private static final class InputEvent extends org.bukkit.event.Event implements org.bukkit.event.Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private boolean cancelled;
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean value) { cancelled = value; }
        @Override public HandlerList getHandlers() { return HANDLERS; }
    }

    void reload(EnchantmentCatalog candidate) {
        stopAll();
        catalog = candidate;
        inputs.clearWarnings();
    }

    private void stopAll() {
        for (Running running : new ArrayList<>(active.values())) running.instance.shutdown();
    }

    @Override public void close() {
        closed = true;
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(inputs);
        stopAll();
        globalCooldowns.clear();
        localCooldowns.clear();
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID actor = event.getPlayer().getUniqueId();
        stopActor(actor);
        globalCooldowns.remove(actor);
        localCooldowns.remove(actor);
    }
    @EventHandler public void onPluginDisable(org.bukkit.event.server.PluginDisableEvent event) {
        if (event.getPlugin() == plugin) close();
    }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) { stopActor(event.getPlayer().getUniqueId()); }
    @EventHandler(ignoreCancelled = true) public void onWorldUnload(WorldUnloadEvent event) {
        for (Running running : new ArrayList<>(active.values()))
            if (running.source.world().equals(event.getWorld().getUID())) running.instance.shutdown();
    }
    @EventHandler(ignoreCancelled = true) public void onChunkUnload(org.bukkit.event.world.ChunkUnloadEvent event) {
        for (Running running : new ArrayList<>(active.values()))
            if (running.ownedEntities.values().stream().anyMatch(entity -> {
                var location = entity.getLocation();
                return location.getWorld().equals(event.getWorld())
                        && location.getBlockX() >> 4 == event.getChunk().getX()
                        && location.getBlockZ() >> 4 == event.getChunk().getZ();
            })) running.instance.shutdown();
    }
    private void stopActor(UUID actor) {
        for (Running running : new ArrayList<>(active.values()))
            if (running.source.actor().equals(actor)) running.instance.shutdown();
    }

    private final class Running extends ScriptableEntity {
        final String token;
        final EnchantmentDefinition definition;
        final int level;
        final EnchantmentActions.Source source;
        final Set<String> delivered = new HashSet<>();
        final EnchantmentItemAccess item;
        final boolean stopOnUnequip;
        final Map<UUID, OwnedEntityState> gravity = new HashMap<>();
        final Map<UUID, Entity> ownedEntities = new HashMap<>();
        ScriptInstance instance;
        LivingEntity target;
        boolean failed;
        long remainingTicks;
        int guardTask;
        boolean finalStage;

        Running(String token, EnchantmentDefinition definition, int level, EnchantmentActions.Source source) {
            this.token = token;
            this.definition = definition;
            this.level = level;
            this.source = source;
            remainingTicks = source.lifetimeTicks();
            var facts = source.facts();
            item = facts.get("item") instanceof org.bukkit.inventory.ItemStack stack
                    && facts.get("inventory_slot") instanceof Integer slot
                    ? new EnchantmentItemAccess(source.actor(), slot, stack) : null;
            Object unequip = definition.parametersAt(level).get("stop_on_unequip");
            if (unequip != null && !(unequip instanceof Boolean))
                throw new IllegalArgumentException("stop_on_unequip must be a boolean");
            stopOnUnequip = Boolean.TRUE.equals(unequip);
        }
        @Override public boolean isScriptOwnerActive() {
            Entity actor = getBukkitEntity();
            return !closed && plugin.isEnabled() && actor instanceof LivingEntity && actor.isValid() && !actor.isDead()
                    && (!(actor instanceof Player player) || player.isOnline())
                    && actor.getWorld().getUID().equals(source.world())
                    && (!stopOnUnequip || item != null && item.isEquipped());
        }
        @Override public Entity getBukkitEntity() { return Bukkit.getEntity(source.actor()); }
        @Override public Location getLocation() {
            Entity actor = getBukkitEntity();
            return actor == null ? null : actor.getLocation();
        }
        @Override public String getContextKey() { return "enchantment"; }
        @Override public Set<ScriptHook> getSupportedHooks() { return hooks; }
        @Override public Map<String, Long> getGlobalCooldownStore() {
            return globalCooldowns.computeIfAbsent(source.actor(), ignored -> new HashMap<>());
        }
        @Override public Map<String, Long> getLocalCooldownStore(com.magmaguy.magmacore.scripting.ScriptDefinition script) {
            return localCooldowns.computeIfAbsent(source.actor(), ignored -> new HashMap<>())
                    .computeIfAbsent(definition.id(), ignored -> new HashMap<>());
        }
        @Override public LuaTable buildContextTable(ScriptInstance ignored) {
            return lua(Map.of("id", definition.id(), "level", level)).checktable();
        }
        @Override public LuaValue resolveExtraContext(String key, ScriptInstance instance) {
            return switch (key) {
                case "parameters" -> lua(definition.parametersAt(level));
                case "source" -> lua(source.facts());
                case "item" -> item == null ? LuaValue.NIL : item.table();
                case "target" -> target == null ? LuaValue.NIL : LuaLivingEntityTable.build(target);
                case "player" -> getBukkitEntity() instanceof Player player ? LuaLivingEntityTable.build(player) : LuaValue.NIL;
                case "action" -> {
                    LuaTable table = lua(Map.of("id", source.attackId().toString(), "instance", token,
                            "actor", source.actor().toString(), "world", source.world().toString())).checktable();
                    table.set("stop", new ZeroArgFunction() {
                        @Override public LuaValue call() { instance.shutdown(); return LuaValue.NIL; }
                    });
                    table.set("replace_previous", LuaTableSupport.tableMethod(table, args -> {
                        for (Running previous : new ArrayList<>(active.values()))
                            if (previous != this && previous.definition.id().equals(definition.id())
                                    && previous.source.actor().equals(source.actor())) previous.instance.shutdown();
                        return LuaValue.NIL;
                    }));
                    table.set("has_previous", LuaTableSupport.tableMethod(table, args -> LuaValue.valueOf(
                            active.values().stream().anyMatch(previous -> previous != this
                                    && previous.definition.id().equals(definition.id())
                                    && previous.source.actor().equals(source.actor())))));
                    // Scripts opt into removal only after using the ordinary world spawn operation.
                    table.set("own_entity", LuaTableSupport.tableMethod(table, args -> {
                        Entity entity = Bukkit.getEntity(UUID.fromString(args.checkjstring(1)));
                        if (entity == null || !entity.isValid() || entity instanceof Player
                                || !entity.getWorld().getUID().equals(source.world())) return LuaValue.FALSE;
                        if (ownedEntities.containsKey(entity.getUniqueId())) return LuaValue.TRUE;
                        instance.ownCleanup(() -> {
                            ownedEntities.remove(entity.getUniqueId());
                            entity.remove();
                        });
                        ownedEntities.put(entity.getUniqueId(), entity);
                        entity.setPersistent(false);
                        return LuaValue.TRUE;
                    }));
                    table.set("place_block", LuaTableSupport.tableMethod(table, args -> {
                        var world = Bukkit.getWorld(source.world());
                        int x = args.checkint(1), y = args.checkint(2), z = args.checkint(3);
                        if (!(getBukkitEntity() instanceof Player player) || world == null
                                || !world.isChunkLoaded(x >> 4, z >> 4)
                                || y <= world.getMinHeight() || y >= world.getMaxHeight()) return LuaValue.FALSE;
                        var expected = org.bukkit.Material.matchMaterial(args.checkjstring(4));
                        var replacement = Bukkit.createBlockData(args.checkjstring(5));
                        var stack = source.facts().get("item") instanceof org.bukkit.inventory.ItemStack captured ? captured : null;
                        var hand = Integer.valueOf(40).equals(source.facts().get("inventory_slot"))
                                ? org.bukkit.inventory.EquipmentSlot.OFF_HAND : org.bukkit.inventory.EquipmentSlot.HAND;
                        return LuaValue.valueOf(com.magmaguy.magmacore.scripting.ScriptBlockActions
                                .placeBlock(player, world.getBlockAt(x, y, z), expected, replacement, stack, hand));
                    }));
                    table.set("break_block", LuaTableSupport.tableMethod(table, args -> {
                        var world = Bukkit.getWorld(source.world());
                        int x = args.checkint(1), y = args.checkint(2), z = args.checkint(3);
                        if (!(getBukkitEntity() instanceof Player player) || world == null
                                || !world.isChunkLoaded(x >> 4, z >> 4)
                                || y < world.getMinHeight() || y >= world.getMaxHeight()) return LuaValue.FALSE;
                        var expected = org.bukkit.Material.matchMaterial(args.checkjstring(4));
                        org.bukkit.inventory.ItemStack drop = null;
                        if (!args.arg(5).isnil()) {
                            var material = org.bukkit.Material.matchMaterial(args.checkjstring(5));
                            int amount = args.optint(6, 1);
                            if (material == null || !material.isItem() || material.isAir() || amount < 1)
                                throw new IllegalArgumentException("Invalid authored mining drop");
                            drop = new org.bukkit.inventory.ItemStack(material, amount);
                        }
                        return LuaValue.valueOf(com.magmaguy.magmacore.scripting.ScriptBlockActions
                                .breakBlock(player, world.getBlockAt(x, y, z), expected, drop));
                    }));
                    table.set("temporary_block", LuaTableSupport.tableMethod(table, args -> {
                        var world = Bukkit.getWorld(source.world());
                        int x = args.checkint(1), y = args.checkint(2), z = args.checkint(3);
                        int ticks = args.optint(5, 0);
                        if (ticks < 0) throw new IllegalArgumentException("Block duration must not be negative");
                        if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)
                                || y < world.getMinHeight() || y >= world.getMaxHeight()) return LuaValue.FALSE;
                        var lease = TemporaryBlockManager.replaceOwned(world.getBlockAt(x, y, z),
                                Bukkit.createBlockData(args.checkjstring(4)), plugin);
                        if (lease == null) return LuaValue.FALSE;
                        instance.ownCleanup(lease::close);
                        if (ticks > 0) instance.ownLater(ticks, lease::close);
                        return LuaValue.TRUE;
                    }));
                    table.set("can_own_block", LuaTableSupport.tableMethod(table, args -> {
                        var world = Bukkit.getWorld(source.world());
                        int x = args.checkint(1), y = args.checkint(2), z = args.checkint(3);
                        return LuaValue.valueOf(world != null && world.isChunkLoaded(x >> 4, z >> 4)
                                && y >= world.getMinHeight() && y < world.getMaxHeight()
                                && TemporaryBlockManager.canOwn(world.getBlockAt(x, y, z)));
                    }));
                    table.set("temporary_gravity", LuaTableSupport.tableMethod(table, args -> {
                        Entity entity = Bukkit.getEntity(UUID.fromString(args.checkjstring(1)));
                        var lease = OwnedEntityState.gravity(entity, args.checkboolean(2), plugin);
                        if (lease == null) return LuaValue.FALSE;
                        UUID id = entity.getUniqueId();
                        gravity.put(id, lease);
                        instance.ownCleanup(() -> { gravity.remove(id, lease); lease.close(); });
                        return LuaValue.TRUE;
                    }));
                    table.set("restore_gravity", LuaTableSupport.tableMethod(table, args -> {
                        var lease = gravity.remove(UUID.fromString(args.checkjstring(1)));
                        if (lease == null) return LuaValue.FALSE;
                        lease.close();
                        return LuaValue.TRUE;
                    }));
                    table.set("temporary_scale", LuaTableSupport.tableMethod(table, args -> {
                        Entity entity = Bukkit.getEntity(UUID.fromString(args.checkjstring(1)));
                        if (!(entity instanceof LivingEntity living)) return LuaValue.FALSE;
                        var lease = OwnedEntityState.scale(living, args.checkdouble(2), plugin);
                        if (lease == null) return LuaValue.FALSE;
                        instance.ownCleanup(lease::close);
                        return LuaValue.TRUE;
                    }));
                    table.set("temporary_potion", LuaTableSupport.tableMethod(table, args -> {
                        Entity entity = Bukkit.getEntity(UUID.fromString(args.checkjstring(1)));
                        var type = org.bukkit.potion.PotionEffectType.getByName(args.checkjstring(2));
                        int ticks = args.checkint(3), amplifier = args.checkint(4);
                        int expiry = Math.addExact(ticks, 1);
                        if (!(entity instanceof LivingEntity living) || type == null || ticks < 1 || amplifier < 0)
                            return LuaValue.FALSE;
                        var lease = OwnedEntityState.potion(living, new org.bukkit.potion.PotionEffect(type, ticks, amplifier), plugin);
                        if (lease == null) return LuaValue.FALSE;
                        instance.ownCleanup(lease::close);
                        instance.ownLater(expiry, lease::close);
                        return LuaValue.TRUE;
                    }));
                    yield table;
                }
                default -> LuaValue.NIL;
            };
        }
        @Override public boolean handleScriptError(String context, Exception failure) {
            failed = true;
            plugin.getLogger().warning("Enchantment " + definition.id() + " action " + source.attackId()
                    + " failed in " + context + ": " + failure.getMessage());
            return true;
        }
        @Override public void onShutdown() { active.remove(token, this); }
    }

    private static LuaValue lua(Object value) {
        if (value instanceof UUID id) return LuaValue.valueOf(id.toString());
        if (value instanceof org.bukkit.inventory.ItemStack item) {
            var levels = new EnchantmentItems(EnchantmentDefinitions::resolve, EnchantmentItemProfile::vanilla).inspect(item);
            return lua(Map.of("material", item.getType().name(), "amount", item.getAmount(), "enchantments", levels));
        }
        if (value instanceof Map<?, ?> map) {
            LuaTable table = new LuaTable();
            map.forEach((key, entry) -> table.set((String) key, lua(entry)));
            return table;
        }
        if (value instanceof List<?> list) {
            LuaTable table = new LuaTable();
            for (int index = 0; index < list.size(); index++) table.set(index + 1, lua(list.get(index)));
            return table;
        }
        if (value instanceof Boolean flag) return LuaValue.valueOf(flag);
        if (value instanceof Number number) return LuaValue.valueOf(number.doubleValue());
        return LuaValue.valueOf((String) value);
    }
    private static String text(Map<String, Object> request, String key) {
        if (!(request.get(key) instanceof String text)) throw new IllegalArgumentException("Expected " + key);
        return text;
    }
    private static void requireKeys(Map<String, Object> request, String... keys) {
        if (!request.keySet().equals(Set.of(keys))) throw new IllegalArgumentException("Invalid action fields");
    }
    private static Map<String, Object> response(EnchantmentActions.Status status) { return Map.of("status", status.name()); }
}
