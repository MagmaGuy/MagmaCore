package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.scripting.tables.LuaLivingEntityTable;
import com.magmaguy.magmacore.scripting.tables.LuaTableSupport;
import com.magmaguy.magmacore.scripting.tables.LuaWorldTable;
import com.magmaguy.magmacore.scripting.zones.Cuboid;
import com.magmaguy.magmacore.scripting.zones.Cylinder;
import com.magmaguy.magmacore.scripting.zones.ScriptZone;
import com.magmaguy.magmacore.scripting.zones.Sphere;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.*;

/**
 * Represents a running instance of a Lua script bound to a {@link ScriptableEntity}.
 * <p>
 * Manages the Lua VM lifecycle, per-entity state, task ownership, and hook dispatch.
 * The Lua table is lazily instantiated on first {@link #handleEvent} call.
 */
public class ScriptInstance {

    @Getter
    private final ScriptDefinition definition;
    @Getter
    private final ScriptableEntity entity;
    private final LuaTable stateTable = new LuaTable();
    private final Map<Integer, OwnedTask> ownedTasks = new LinkedHashMap<>();

    private final Map<Integer, ScriptZone> zoneWatches = new LinkedHashMap<>();
    private int nextZoneHandle = 1;

    private LuaTable scriptTable;
    private Integer tickTaskId = null;
    private boolean closed = false;
    private Event currentEvent = null;
    private LivingEntity currentEventActor = null;
    private LivingEntity currentDirectTarget = null;

    public ScriptInstance(ScriptDefinition definition, ScriptableEntity entity) {
        this.definition = definition;
        this.entity = entity;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Eagerly instantiates the Lua VM and registers the per-tick task (if the script declares
     * on_game_tick) WITHOUT firing any hook. Hosts that dispatch their own spawn event (e.g.
     * EliteMobs bosses, whose on_spawn arrives as a separate event) call this on creation so a
     * tick-only script still gets its tick loop — instead of relying on the first hook dispatch.
     * Idempotent and safe to call once at startup.
     */
    public void start() {
        if (closed) return;
        ensureScriptTable();
    }

    /** The actor (e.g. interacting/triggering player) of the event being dispatched, or null. */
    public LivingEntity getCurrentEventActor() {
        return currentEventActor;
    }

    /** The event currently being dispatched, or null (e.g. during on_tick / on_spawn). */
    public Event getCurrentEvent() {
        return currentEvent;
    }

    /** The direct target of the event being dispatched, or null (e.g. during on_tick). */
    public LivingEntity getCurrentDirectTarget() {
        return currentDirectTarget;
    }

    // ── Public task / callback API for rich ScriptableEntity implementations ─────
    // Lets entities that supply their own context tables (e.g. EliteMobs bosses) schedule
    // OWNED tasks (auto-cancelled on shutdown) and invoke Lua callbacks under the same
    // 50ms error/time-budget watchdog as built-in hooks — so they reuse this single runtime
    // instead of maintaining a parallel one.

    /** Schedule a one-shot owned Java task; auto-cancelled on shutdown. Returns its id. */
    public int ownLater(int ticks, Runnable runnable) {
        JavaPlugin plugin = MagmaCore.getInstance().getRequestingPlugin();
        int[] holder = new int[1];
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ownedTasks.remove(holder[0]);
            runnable.run();
        }, ticks);
        holder[0] = task.getTaskId();
        ownedTasks.put(holder[0], () -> Bukkit.getScheduler().cancelTask(holder[0]));
        return holder[0];
    }

    /** Schedule a repeating owned Java task; auto-cancelled on shutdown. Returns its id. */
    public int ownRepeating(int delayTicks, int intervalTicks, Runnable runnable) {
        JavaPlugin plugin = MagmaCore.getInstance().getRequestingPlugin();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, intervalTicks);
        int id = task.getTaskId();
        ownedTasks.put(id, () -> Bukkit.getScheduler().cancelTask(id));
        return id;
    }

    /** Schedule a one-shot owned Lua callback, invoked with a fresh context table. */
    public int ownLuaLater(int ticks, LuaFunction callback) {
        return ownLaterTask(ticks, callback);
    }

    /** Schedule a repeating owned Lua callback, each invoked with a fresh context table. */
    public int ownLuaRepeating(int delayTicks, int intervalTicks, LuaFunction callback) {
        return ownRepeatingTask(delayTicks, intervalTicks, callback);
    }

    /** Cancel an owned task by id. */
    public void cancelOwned(int taskId) {
        cancelOwnedTask(taskId);
    }

    /** Invoke a Lua callback with explicit args under the time/error watchdog. */
    public void invokeOwnedCallback(String failureContext, LuaFunction callback, LuaValue... args) {
        if (closed) return;
        long startNanos = System.nanoTime();
        try {
            callback.invoke(LuaValue.varargsOf(args));
        } catch (Exception exception) {
            logLuaError(failureContext, exception);
            shutdown();
            return;
        }
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        if (elapsedMillis > 50) {
            Logger.warn("[Lua] " + definition.getFileName() + " took " + elapsedMillis + "ms in '"
                    + failureContext + "' (limit: 50ms) — script disabled to prevent lag.");
            shutdown();
        }
    }

    // ── Event dispatch ───────────────────────────────────────────────────

    /**
     * Invokes the Lua hook function with a lazily-built context table.
     * Creates the Lua VM on the first call.
     */
    public void handleEvent(ScriptHook hook, Event event,
                            LivingEntity directTarget, LivingEntity eventActor) {
        if (closed || hook == null) return;

        ensureScriptTable();

        LuaValue function = scriptTable.get(hook.getKey());
        if (!definition.getHooks().contains(hook) || !function.isfunction()) return;

        long startNanos = System.nanoTime();
        currentEvent = event;
        currentEventActor = eventActor;
        currentDirectTarget = directTarget;
        try {
            function.checkfunction().call(buildContext(event, directTarget, eventActor));
        } catch (Exception exception) {
            logLuaError(hook.getKey(), exception);
            shutdown();
            return;
        } finally {
            currentEvent = null;
            currentEventActor = null;
            currentDirectTarget = null;
        }

        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        if (elapsedMillis > 50) {
            Logger.warn("[Lua] " + definition.getFileName() + " took " + elapsedMillis + "ms in '"
                    + hook.getKey() + "' (limit: 50ms) — script disabled to prevent lag.");
            shutdown();
        }
    }

    /**
     * Called each server tick when ON_TICK is supported.
     */
    public void onTick() {
        if (closed) return;
        if (!entity.isScriptOwnerActive()) {
            shutdown();
            return;
        }
        tickZoneWatches();
        if (definition.supportsHook(ScriptHook.ON_TICK)) {
            handleEvent(ScriptHook.ON_TICK, null, null, null);
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Shuts down this script instance: cancels all owned tasks, clears state,
     * and notifies the entity.
     */
    public void shutdown() {
        if (closed) return;
        closed = true;

        if (tickTaskId != null) {
            Bukkit.getScheduler().cancelTask(tickTaskId);
            tickTaskId = null;
        }

        for (OwnedTask task : new ArrayList<>(ownedTasks.values())) {
            task.cancel();
        }
        ownedTasks.clear();

        for (ScriptZone zone : zoneWatches.values()) {
            zone.shutdown();
        }
        zoneWatches.clear();

        entity.onShutdown();
    }

    // ── Lazy VM creation ─────────────────────────────────────────────────

    private void ensureScriptTable() {
        if (scriptTable != null) return;
        scriptTable = definition.instantiate();
        updateTickRegistration();
    }

    // ── Context building ─────────────────────────────────────────────────

    private LuaValue buildContext(Event event, LivingEntity directTarget, LivingEntity eventActor) {
        LuaTable context = new LuaTable();
        context.set("state", stateTable);

        LuaTable metatable = new LuaTable();
        metatable.set("__index", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LuaTable owner = args.arg1().checktable();
                LuaValue keyValue = args.arg(2);
                if (!keyValue.isstring()) return LuaValue.NIL;

                String key = keyValue.tojstring();
                LuaValue resolved = resolveContextValue(key, event, directTarget, eventActor);
                if (!resolved.isnil()) {
                    owner.rawset(key, resolved);
                }
                return resolved;
            }
        });
        context.setmetatable(metatable);
        return context;
    }

    private LuaValue resolveContextValue(String key, Event event,
                                         LivingEntity directTarget, LivingEntity eventActor) {
        // 1) The entity's own primary table (context.boss / npc / prop).
        if (key.equals(entity.getContextKey())) {
            return entity.buildContextTable(this);
        }
        // 2) Entity overrides/extensions FIRST, so rich entities (bosses) can supply their own
        //    cooldowns/scheduler/zones/world/players/entities/script/etc. Simple entities (props,
        //    NPCs) return NIL here and inherit the Magmacore defaults below.
        LuaValue custom = entity.resolveExtraContext(key, this);
        if (custom != null && !custom.isnil()) {
            return custom;
        }
        // 3) Magmacore built-in defaults.
        return switch (key) {
            case "log" -> createLogTable();
            case "cooldowns" -> createCooldownTable();
            case "scheduler" -> createSchedulerTable();
            case "world" -> {
                Location loc = entity.getLocation();
                yield (loc != null && loc.getWorld() != null)
                        ? LuaWorldTable.build(loc.getWorld())
                        : LuaValue.NIL;
            }
            case "zones" -> createZonesTable();
            case "event" -> createEventTable();
            case "player" -> {
                LivingEntity player = resolveContextPlayer(directTarget, eventActor);
                yield player == null ? LuaValue.NIL : LuaLivingEntityTable.build(player);
            }
            default -> LuaValue.NIL;
        };
    }

    private LivingEntity resolveContextPlayer(LivingEntity directTarget, LivingEntity eventActor) {
        if (eventActor instanceof Player) return eventActor;
        if (directTarget instanceof Player) return directTarget;
        return null;
    }

    // ── Log table ────────────────────────────────────────────────────────

    private LuaTable createLogTable() {
        LuaTable log = new LuaTable();
        log.set("info", logFunction(log, message -> Logger.info("[Lua] " + message)));
        log.set("warn", logFunction(log, Logger::warn));
        log.set("error", logFunction(log, Logger::warn));
        return log;
    }

    private VarArgFunction logFunction(LuaTable owner, java.util.function.Consumer<String> consumer) {
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Varargs strippedArgs = LuaTableSupport.stripMethodSelf(args, owner);
                if (strippedArgs.narg() > 0 && strippedArgs.arg1().isstring()) {
                    consumer.accept(strippedArgs.arg1().tojstring());
                }
                return LuaValue.NIL;
            }
        };
    }

    // ── Scheduler table ──────────────────────────────────────────────────

    private LuaTable createSchedulerTable() {
        LuaTable scheduler = new LuaTable();
        scheduler.set("run_later", method(scheduler, args -> {
            int ticks = args.checkint(1);
            LuaFunction callback = args.checkfunction(2);
            return LuaValue.valueOf(ownLaterTask(ticks, callback));
        }));
        scheduler.set("run_repeating", method(scheduler, args -> {
            int delay = args.checkint(1);
            int interval = args.checkint(2);
            LuaFunction callback = args.checkfunction(3);
            return LuaValue.valueOf(ownRepeatingTask(delay, interval, callback));
        }));
        scheduler.set("cancel", method(scheduler, args -> {
            cancelOwnedTask(args.checkint(1));
            return LuaValue.NIL;
        }));
        // Aliases matching the EliteMobs boss-power scheduler convention, so the same script
        // style works on every surface: run_after == run_later; run_every(interval) ==
        // run_repeating(0, interval); cancel_task == cancel.
        scheduler.set("run_after", method(scheduler, args -> {
            int ticks = args.checkint(1);
            LuaFunction callback = args.checkfunction(2);
            return LuaValue.valueOf(ownLaterTask(ticks, callback));
        }));
        scheduler.set("run_every", method(scheduler, args -> {
            int interval = args.checkint(1);
            LuaFunction callback = args.checkfunction(2);
            return LuaValue.valueOf(ownRepeatingTask(0, interval, callback));
        }));
        scheduler.set("cancel_task", method(scheduler, args -> {
            cancelOwnedTask(args.checkint(1));
            return LuaValue.NIL;
        }));
        return scheduler;
    }

    // ── Cooldowns ────────────────────────────────────────────────────────

    public boolean isCooldownReady(String key) {
        Map<String, Long> cooldowns = entity.getLocalCooldownStore(definition);
        Long expiresAt = cooldowns.get(key);
        if (expiresAt == null) return true;
        if (expiresAt <= System.nanoTime()) {
            cooldowns.remove(key);
            return true;
        }
        return false;
    }

    public long getCooldownRemainingTicks(String key) {
        Map<String, Long> cooldowns = entity.getLocalCooldownStore(definition);
        Long expiresAt = cooldowns.get(key);
        if (expiresAt == null) return 0L;
        long remainingNanos = expiresAt - System.nanoTime();
        if (remainingNanos <= 0) {
            cooldowns.remove(key);
            return 0L;
        }
        return Math.max(1L, remainingNanos / 50_000_000L);
    }

    public void setCooldown(String key, long ticks) {
        Map<String, Long> cooldowns = entity.getLocalCooldownStore(definition);
        if (ticks <= 0) {
            cooldowns.remove(key);
            return;
        }
        cooldowns.put(key, System.nanoTime() + ticks * 50_000_000L);
    }

    private String resolveCooldownKey(Varargs args, int index) {
        if (args.narg() < index || args.arg(index).isnil()) {
            return "__lua:" + definition.getFileName();
        }
        return args.checkjstring(index);
    }

    private LuaTable createCooldownTable() {
        LuaTable cd = new LuaTable();
        cd.set("local_ready", method(cd, args ->
                LuaValue.valueOf(isCooldownReady(resolveCooldownKey(args, 1)))));
        cd.set("local_remaining", method(cd, args ->
                LuaValue.valueOf(getCooldownRemainingTicks(resolveCooldownKey(args, 1)))));
        cd.set("check_local", method(cd, args -> {
            String key = resolveCooldownKey(args, 1);
            int duration = args.checkint(2);
            if (!isCooldownReady(key)) return LuaValue.FALSE;
            setCooldown(key, duration);
            return LuaValue.TRUE;
        }));
        cd.set("set_local", method(cd, args -> {
            setCooldown(resolveCooldownKey(args, 2), args.checklong(1));
            return LuaValue.NIL;
        }));
        // Global cooldowns — shared across all scripts on the same owner
        Map<String, Long> globalStore = entity.getGlobalCooldownStore();
        cd.set("global_ready", method(cd, args ->
                LuaValue.valueOf(isGlobalCooldownReady(globalStore))));
        cd.set("set_global", method(cd, args -> {
            setGlobalCooldown(globalStore, args.checklong(1));
            return LuaValue.NIL;
        }));
        return cd;
    }

    private static boolean isGlobalCooldownReady(Map<String, Long> store) {
        Long expiresAt = store.get("__global");
        if (expiresAt == null) return true;
        if (expiresAt <= System.nanoTime()) {
            store.remove("__global");
            return true;
        }
        return false;
    }

    private static void setGlobalCooldown(Map<String, Long> store, long ticks) {
        if (ticks <= 0) {
            store.remove("__global");
            return;
        }
        store.put("__global", System.nanoTime() + ticks * 50_000_000L);
    }

    // ── Task management ──────────────────────────────────────────────────

    private void updateTickRegistration() {
        boolean shouldTick = !closed
                && entity.isScriptOwnerActive()
                && (definition.supportsHook(ScriptHook.ON_TICK) || !zoneWatches.isEmpty());
        if (shouldTick && tickTaskId == null) {
            JavaPlugin plugin = MagmaCore.getInstance().getRequestingPlugin();
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, this::onTick, 1L, 1L);
            tickTaskId = task.getTaskId();
        } else if (!shouldTick && tickTaskId != null) {
            Bukkit.getScheduler().cancelTask(tickTaskId);
            tickTaskId = null;
        }
    }

    private int ownLaterTask(int ticks, LuaFunction callback) {
        JavaPlugin plugin = MagmaCore.getInstance().getRequestingPlugin();
        int[] taskIdHolder = new int[1];
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ownedTasks.remove(taskIdHolder[0]);
            runCallback(callback);
        }, ticks);
        taskIdHolder[0] = task.getTaskId();
        ownedTasks.put(taskIdHolder[0], () -> Bukkit.getScheduler().cancelTask(taskIdHolder[0]));
        return taskIdHolder[0];
    }

    private int ownRepeatingTask(int delay, int interval, LuaFunction callback) {
        JavaPlugin plugin = MagmaCore.getInstance().getRequestingPlugin();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> runCallback(callback), delay, interval);
        int taskId = task.getTaskId();
        ownedTasks.put(taskId, () -> Bukkit.getScheduler().cancelTask(taskId));
        return taskId;
    }

    private void cancelOwnedTask(int taskId) {
        OwnedTask task = ownedTasks.remove(taskId);
        if (task != null) {
            task.cancel();
        }
    }

    private void runCallback(LuaFunction callback) {
        if (closed) return;
        long startNanos = System.nanoTime();
        try {
            callback.call(buildContext(null, null, null));
        } catch (Exception exception) {
            logLuaError("scheduled callback", exception);
            shutdown();
            return;
        }

        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        if (elapsedMillis > 50) {
            Logger.warn("[Lua] " + definition.getFileName() + " took " + elapsedMillis + "ms in '"
                    + "scheduled callback' (limit: 50ms) — script disabled to prevent lag.");
            shutdown();
        }
    }

    // ── Zones table ─────────────────────────────────────────────────────

    private LuaTable createZonesTable() {
        LuaTable zones = new LuaTable();

        // create_sphere(x, y, z, radius) -> zone handle
        zones.set("create_sphere", method(zones, args -> {
            double x = args.checkdouble(1);
            double y = args.checkdouble(2);
            double z = args.checkdouble(3);
            double radius = args.checkdouble(4);
            Location loc = entity.getLocation();
            World world = loc != null ? loc.getWorld() : null;
            if (world == null) return LuaValue.NIL;
            Sphere shape = new Sphere(radius, new Location(world, x, y, z), 1);
            return LuaValue.valueOf(registerZone(new ScriptZone(shape)));
        }));

        // create_cylinder(x, y, z, radius, height) -> zone handle
        zones.set("create_cylinder", method(zones, args -> {
            double x = args.checkdouble(1);
            double y = args.checkdouble(2);
            double z = args.checkdouble(3);
            double radius = args.checkdouble(4);
            double height = args.checkdouble(5);
            Location loc = entity.getLocation();
            World world = loc != null ? loc.getWorld() : null;
            if (world == null) return LuaValue.NIL;
            Cylinder shape = new Cylinder(new Location(world, x, y, z), radius, height, 1);
            return LuaValue.valueOf(registerZone(new ScriptZone(shape)));
        }));

        // create_cuboid(x, y, z, xSize, ySize, zSize) -> zone handle
        zones.set("create_cuboid", method(zones, args -> {
            double x = args.checkdouble(1);
            double y = args.checkdouble(2);
            double z = args.checkdouble(3);
            float xSize = (float) args.checkdouble(4);
            float ySize = (float) args.checkdouble(5);
            float zSize = (float) args.checkdouble(6);
            Location loc = entity.getLocation();
            World world = loc != null ? loc.getWorld() : null;
            if (world == null) return LuaValue.NIL;
            Cuboid shape = new Cuboid(xSize, ySize, zSize, 0f, 0f, 0f, new Location(world, x, y, z));
            return LuaValue.valueOf(registerZone(new ScriptZone(shape)));
        }));

        // watch(zone_handle, on_enter_callback, on_leave_callback) -> starts tracking
        zones.set("watch", method(zones, args -> {
            int handle = args.checkint(1);
            LuaFunction onEnter = args.isfunction(2) ? args.checkfunction(2) : null;
            LuaFunction onLeave = args.isfunction(3) ? args.checkfunction(3) : null;
            ScriptZone zone = zoneWatches.get(handle);
            if (zone == null) return LuaValue.NIL;
            if (onEnter != null) {
                zone.setOnEnter((player, z) ->
                        handleEvent(ScriptHook.ON_ZONE_ENTER, null, player, player));
            }
            if (onLeave != null) {
                zone.setOnLeave((player, z) ->
                        handleEvent(ScriptHook.ON_ZONE_LEAVE, null, player, player));
            }
            return LuaValue.TRUE;
        }));

        // unwatch(zone_handle) -> stops tracking
        zones.set("unwatch", method(zones, args -> {
            int handle = args.checkint(1);
            ScriptZone zone = zoneWatches.remove(handle);
            if (zone != null) zone.shutdown();
            updateTickRegistration();
            return LuaValue.NIL;
        }));

        return zones;
    }

    // ── Event table ──────────────────────────────────────────────────────

    private LuaValue createEventTable() {
        if (currentEvent == null && currentEventActor == null) return LuaValue.NIL;
        LuaTable eventTable = new LuaTable();
        if (currentEvent instanceof Cancellable cancellable) {
            eventTable.set("is_cancelled", LuaValue.valueOf(cancellable.isCancelled()));
            eventTable.set("cancel", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs args) {
                    cancellable.setCancelled(true);
                    return LuaValue.NIL;
                }
            });
            eventTable.set("uncancel", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs args) {
                    cancellable.setCancelled(false);
                    return LuaValue.NIL;
                }
            });
        } else {
            eventTable.set("is_cancelled", LuaValue.FALSE);
        }
        if (currentEventActor != null) {
            eventTable.set("player", LuaLivingEntityTable.build(currentEventActor));
        }
        return eventTable;
    }

    private int registerZone(ScriptZone zone) {
        int handle = nextZoneHandle++;
        zoneWatches.put(handle, zone);
        updateTickRegistration();
        return handle;
    }

    private void tickZoneWatches() {
        if (zoneWatches.isEmpty()) return;
        Location loc = entity.getLocation();
        if (loc == null || loc.getWorld() == null) return;
        Collection<Player> nearby = loc.getWorld().getPlayers();
        for (ScriptZone zone : zoneWatches.values()) {
            zone.tick(nearby);
        }
    }

    // ── Lua method helper ────────────────────────────────────────────────

    private VarArgFunction method(LuaTable owner, LuaTableSupport.LuaCallback callback) {
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return callback.invoke(LuaTableSupport.stripMethodSelf(args, owner));
            }
        };
    }

    // ── Error reporting ──────────────────────────────────────────────────

    private void logLuaError(String context, Exception exception) {
        String fileName = definition.getFileName();
        String rawMessage = exception.getMessage() != null ? exception.getMessage() : exception.toString();

        String lineInfo = "";
        String errorDetail = rawMessage;
        if (rawMessage.contains(fileName)) {
            int fileStart = rawMessage.indexOf(fileName);
            String afterFile = rawMessage.substring(fileStart + fileName.length());
            if (afterFile.startsWith(":")) {
                afterFile = afterFile.substring(1);
                int spaceIndex = afterFile.indexOf(' ');
                if (spaceIndex > 0) {
                    lineInfo = afterFile.substring(0, spaceIndex);
                    errorDetail = afterFile.substring(spaceIndex + 1).trim();
                }
            }
        }

        StringBuilder message = new StringBuilder();
        message.append("[Lua] Error in '").append(fileName).append("'");
        if (!lineInfo.isEmpty()) {
            message.append(" at line ").append(lineInfo);
        }
        message.append(" during '").append(context).append("':");
        Logger.warn(message.toString());

        if (errorDetail.contains("attempt to call nil")) {
            Logger.warn("[Lua]   -> You tried to call a method or function that doesn't exist.");
            Logger.warn("[Lua]   -> Check the method name for typos, or make sure you're using ':' (colon) for method calls, not '.' (dot).");
        } else if (errorDetail.contains("index expected, got nil")) {
            Logger.warn("[Lua]   -> You tried to access a field on something that is nil (doesn't exist).");
            Logger.warn("[Lua]   -> A variable or table field you're reading hasn't been set yet. Check that earlier code initialized it.");
        } else if (errorDetail.contains("attempt to index")) {
            Logger.warn("[Lua]   -> You tried to access a property on a nil or invalid value.");
            Logger.warn("[Lua]   -> Check that the object exists before accessing its fields.");
        } else if (errorDetail.contains("bad argument")) {
            Logger.warn("[Lua]   -> A function received the wrong type of argument (e.g. string instead of number).");
            Logger.warn("[Lua]   -> Detail: " + errorDetail);
        } else {
            Logger.warn("[Lua]   -> " + errorDetail);
        }

        Logger.warn("[Lua]   -> Script has been disabled for this entity to prevent further errors.");
    }

    // ── Inner types ──────────────────────────────────────────────────────

    @FunctionalInterface
    private interface OwnedTask {
        void cancel();
    }
}
