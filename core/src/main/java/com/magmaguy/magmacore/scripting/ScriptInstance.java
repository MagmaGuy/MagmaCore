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
 * The Lua table is lazily instantiated on the first event or query hook call.
 */
public class ScriptInstance {

    @Getter
    private final ScriptDefinition definition;
    @Getter
    private final ScriptableEntity entity;
    private final LuaTable stateTable = new LuaTable();
    private final Map<Integer, OwnedTask> ownedTasks = new LinkedHashMap<>();
    private final Deque<Runnable> ownedCleanup = new ArrayDeque<>();

    private final Map<Integer, ScriptZone> zoneWatches = new LinkedHashMap<>();
    private int nextZoneHandle = 1;

    private LuaTable scriptTable;
    private Integer tickTaskId = null;
    private boolean closed = false;
    private boolean paused = false;
    private Event currentEvent = null;
    private LivingEntity currentEventActor = null;
    private LivingEntity currentDirectTarget = null;

    // Death phase (see retire). Hosts that never retire keep all of these at their defaults.
    private boolean retiring = false;
    private Integer retirementCapTaskId = null;
    // True while code runs whose newly scheduled tasks outlive the owner: an owner-marked hook
    // call, or the body of a task that itself outlives the owner.
    private boolean schedulingSurvivors = false;
    private int ownedTaskDepth = 0;

    public ScriptInstance(ScriptDefinition definition, ScriptableEntity entity) {
        this.definition = definition;
        this.entity = entity;
    }

    public boolean isClosed() {
        return closed;
    }

    /** True between {@link #retire} and the final shutdown. */
    public boolean isRetiring() {
        return retiring && !closed;
    }

    /** Allows a single-input host to release an idle activation without counting its own lifetime guard. */
    public boolean hasOwnedWorkExcept(int hostGuardTask) {
        return tickTaskId != null || !zoneWatches.isEmpty() || !ownedCleanup.isEmpty()
                || ownedTasks.keySet().stream().anyMatch(id -> id != hostGuardTask);
    }

    /** Register optional Java-owned restoration. Runs once on every shutdown path, including Lua errors. */
    public void ownCleanup(Runnable cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        if (closed) cleanup.run();
        else ownedCleanup.addFirst(cleanup);
    }

    /**
     * Freezes hook and owned-callback execution without discarding the Lua VM or script state.
     * Repeating callbacks remain scheduled and skip paused ticks; due one-shot callbacks wait
     * until the instance resumes.
     */
    public void setPaused(boolean paused) {
        if (closed) return;
        this.paused = paused;
    }

    public boolean isPaused() {
        return paused;
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
    // CPU-time/instruction watchdog as built-in hooks — so they reuse this single runtime
    // instead of maintaining a parallel one.

    /** Schedule a one-shot owned Java task; auto-cancelled on shutdown. Returns its id. */
    public int ownLater(int ticks, Runnable runnable) {
        return ownLater(ticks, runnable, false);
    }

    /**
     * Schedule a one-shot owned Java task. With {@code outlivesOwner}, the task keeps running
     * through {@link #retire}; otherwise retirement cancels it. Shutdown always cancels it.
     */
    public int ownLater(int ticks, Runnable runnable, boolean outlivesOwner) {
        requireOpen();
        boolean survives = survivesRetirement(outlivesOwner);
        JavaPlugin plugin = MagmaCore.getInstance().getRequestingPlugin();
        int[] holder = new int[1];
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (paused) return;
            Bukkit.getScheduler().cancelTask(holder[0]);
            ownedTasks.remove(holder[0]);
            runOwnedTask(survives, runnable);
        }, ticks, 1L);
        holder[0] = task.getTaskId();
        ownedTasks.put(holder[0], new OwnedTask(() -> Bukkit.getScheduler().cancelTask(holder[0]), survives));
        return holder[0];
    }

    /** Schedule a repeating owned Java task; auto-cancelled on shutdown. Returns its id. */
    public int ownRepeating(int delayTicks, int intervalTicks, Runnable runnable) {
        return ownRepeating(delayTicks, intervalTicks, runnable, false);
    }

    /** Repeating variant of {@link #ownLater(int, Runnable, boolean)}. */
    public int ownRepeating(int delayTicks, int intervalTicks, Runnable runnable, boolean outlivesOwner) {
        requireOpen();
        boolean survives = survivesRetirement(outlivesOwner);
        JavaPlugin plugin = MagmaCore.getInstance().getRequestingPlugin();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> {
                    if (!paused) runOwnedTask(survives, runnable);
                },
                delayTicks,
                intervalTicks);
        int id = task.getTaskId();
        ownedTasks.put(id, new OwnedTask(() -> Bukkit.getScheduler().cancelTask(id), survives));
        return id;
    }

    /** Schedule a one-shot owned Lua callback, invoked with a fresh context table. */
    public int ownLuaLater(int ticks, LuaFunction callback) {
        return ownLaterTask(ticks, callback, false);
    }

    /** One-shot owned Lua callback that, with {@code outlivesOwner}, keeps running through {@link #retire}. */
    public int ownLuaLater(int ticks, LuaFunction callback, boolean outlivesOwner) {
        return ownLaterTask(ticks, callback, outlivesOwner);
    }

    /** Schedule a repeating owned Lua callback, each invoked with a fresh context table. */
    public int ownLuaRepeating(int delayTicks, int intervalTicks, LuaFunction callback) {
        return ownRepeatingTask(delayTicks, intervalTicks, callback, false);
    }

    /** Repeating owned Lua callback that, with {@code outlivesOwner}, keeps running through {@link #retire}. */
    public int ownLuaRepeating(int delayTicks, int intervalTicks, LuaFunction callback, boolean outlivesOwner) {
        return ownRepeatingTask(delayTicks, intervalTicks, callback, outlivesOwner);
    }

    /** Cancel an owned task by id. */
    public void cancelOwned(int taskId) {
        cancelOwnedTask(taskId);
    }

    /** Invoke a Lua callback with explicit args under the CPU-time/instruction watchdog. */
    public void invokeOwnedCallback(String failureContext, LuaFunction callback, LuaValue... args) {
        if (closed || paused) return;
        try {
            LuaExecutionBudget.run(() -> callback.invoke(LuaValue.varargsOf(args)));
        } catch (Exception exception) {
            logLuaError(failureContext, exception);
            shutdown();
            return;
        }
    }

    // ── Event dispatch ───────────────────────────────────────────────────

    /**
     * Invokes the Lua hook function with a lazily-built context table.
     * Creates the Lua VM on the first call.
     */
    public void handleEvent(ScriptHook hook, Event event,
                            LivingEntity directTarget, LivingEntity eventActor) {
        invokeHook(hook, event, directTarget, eventActor, false);
    }

    /**
     * Same as {@link #handleEvent(ScriptHook, Event, LivingEntity, LivingEntity)}. With
     * {@code tasksOutliveOwner}, every task this hook call schedules outlives {@link #retire},
     * and so does every task those tasks' callbacks schedule. Owners use this for the hook that
     * announces their own end (for example a boss's on_death).
     */
    public void handleEvent(ScriptHook hook, Event event,
                            LivingEntity directTarget, LivingEntity eventActor,
                            boolean tasksOutliveOwner) {
        invokeHook(hook, event, directTarget, eventActor, tasksOutliveOwner);
    }

    /**
     * Invokes a Lua hook synchronously and returns its immutable scalar result.
     *
     * <p>The hook runs in the same VM, context, ownership lifecycle, and execution watchdog as
     * ordinary events. Returning a table, function, userdata, or thread is a contract failure:
     * the script is logged, shut down, and {@link ScriptQueryResult.Kind#FAILED} is returned.</p>
     */
    public ScriptQueryResult handleQuery(
            ScriptHook hook,
            Event event,
            LivingEntity directTarget,
            LivingEntity eventActor) {
        HookInvocation invocation = invokeHook(hook, event, directTarget, eventActor, false);
        if (invocation.kind() == HookInvocationKind.UNHANDLED) {
            return ScriptQueryResult.unhandled();
        }
        if (invocation.kind() == HookInvocationKind.FAILED) {
            return ScriptQueryResult.failed();
        }
        LuaValue value = invocation.value();
        try {
            if (value.isnil()) return ScriptQueryResult.nil();
            // LuaJ's coercion predicates also consider numbers strings and numeric strings numbers.
            if (value.type() == LuaValue.TSTRING) return ScriptQueryResult.string(value.checkjstring());
            if (value.isboolean()) return ScriptQueryResult.bool(value.checkboolean());
            if (value.isnumber()) return ScriptQueryResult.number(value.checkdouble());
            throw new IllegalArgumentException(
                    "Lua query hooks may return only nil, string, number, or boolean");
        } catch (Exception exception) {
            logLuaError(hook.getKey() + " result", exception);
            shutdown();
            return ScriptQueryResult.failed();
        }
    }

    private HookInvocation invokeHook(
            ScriptHook hook,
            Event event,
            LivingEntity directTarget,
            LivingEntity eventActor,
            boolean tasksOutliveOwner) {
        // A retiring owner no longer receives hooks; only its surviving tasks still run.
        if (closed || retiring || paused || hook == null || !definition.supportsHook(hook)) {
            return HookInvocation.unhandled();
        }

        Event previousEvent = currentEvent;
        LivingEntity previousEventActor = currentEventActor;
        LivingEntity previousDirectTarget = currentDirectTarget;
        boolean previousSchedulingSurvivors = schedulingSurvivors;
        try {
            ensureScriptTable();
            LuaValue function = scriptTable.get(hook.getKey());
            if (!function.isfunction()) return HookInvocation.unhandled();

            currentEvent = event;
            currentEventActor = eventActor;
            currentDirectTarget = directTarget;
            schedulingSurvivors = tasksOutliveOwner;
            LuaValue result = LuaExecutionBudget.run(() ->
                    function.checkfunction().call(buildContext(event, directTarget, eventActor)));
            return HookInvocation.handled(result);
        } catch (Exception exception) {
            logLuaError(hook.getKey(), exception);
            shutdown();
            return HookInvocation.failed();
        } finally {
            currentEvent = previousEvent;
            currentEventActor = previousEventActor;
            currentDirectTarget = previousDirectTarget;
            schedulingSurvivors = previousSchedulingSurvivors;
        }
    }

    /**
     * Called each server tick when ON_TICK is supported.
     */
    public void onTick() {
        if (closed || retiring || paused) return;
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
        // Only set by retire(); null for every instance that never entered the death phase.
        if (retirementCapTaskId != null) {
            Bukkit.getScheduler().cancelTask(retirementCapTaskId);
            retirementCapTaskId = null;
        }

        for (OwnedTask task : new ArrayList<>(ownedTasks.values())) {
            task.cancel();
        }
        ownedTasks.clear();

        for (ScriptZone zone : zoneWatches.values()) {
            zone.shutdown();
        }
        zoneWatches.clear();

        while (!ownedCleanup.isEmpty()) {
            try { ownedCleanup.removeFirst().run(); }
            catch (RuntimeException failure) { logLuaError("owned cleanup", failure); }
        }
        entity.onShutdown();
    }

    /**
     * Opt-in death phase for owners whose script may finish work after the owner itself ends
     * (an EliteMobs boss dying). Hosts that never call this keep {@link #shutdown()} unchanged.
     *
     * <p>Retiring stops the tick task, hook dispatch and zone watches, cancels every owned task
     * that does not outlive the owner, and clears any pause. Owned tasks that outlive the owner
     * keep running, and callbacks still run through {@link #invokeOwnedCallback}. The full
     * {@link #shutdown()}, with its owned cleanups, follows when the last surviving task ends,
     * when {@code maxTicks} pass, or when the host calls it. If nothing survives, it happens now.
     *
     * @param maxTicks    hard limit on the death phase, in ticks
     * @param onCapReached runs once, before the shutdown, if the limit cuts surviving tasks; may be null
     * @return true when the instance stays in the death phase, false when it is already shut down
     */
    public boolean retire(int maxTicks, Runnable onCapReached) {
        if (closed) return false;
        if (retiring) return true;
        retiring = true;
        paused = false;

        if (tickTaskId != null) {
            Bukkit.getScheduler().cancelTask(tickTaskId);
            tickTaskId = null;
        }
        for (ScriptZone zone : zoneWatches.values()) {
            zone.shutdown();
        }
        zoneWatches.clear();

        Iterator<OwnedTask> tasks = ownedTasks.values().iterator();
        while (tasks.hasNext()) {
            OwnedTask task = tasks.next();
            if (task.outlivesOwner()) continue;
            tasks.remove();
            task.cancel();
        }

        if (ownedTasks.isEmpty() || maxTicks <= 0) {
            shutdown();
            return false;
        }
        JavaPlugin plugin = MagmaCore.getInstance().getRequestingPlugin();
        retirementCapTaskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            retirementCapTaskId = null;
            if (closed) return;
            if (onCapReached != null) {
                try { onCapReached.run(); }
                catch (RuntimeException failure) { logLuaError("death phase limit", failure); }
            }
            shutdown();
        }, maxTicks).getTaskId();
        return true;
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
            return LuaValue.valueOf(ownLaterTask(ticks, callback, false));
        }));
        scheduler.set("run_repeating", method(scheduler, args -> {
            int delay = args.checkint(1);
            int interval = args.checkint(2);
            LuaFunction callback = args.checkfunction(3);
            return LuaValue.valueOf(ownRepeatingTask(delay, interval, callback, false));
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
            return LuaValue.valueOf(ownLaterTask(ticks, callback, false));
        }));
        scheduler.set("run_every", method(scheduler, args -> {
            int interval = args.checkint(1);
            LuaFunction callback = args.checkfunction(2);
            return LuaValue.valueOf(ownRepeatingTask(0, interval, callback, false));
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
                && !retiring
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

    private int ownLaterTask(int ticks, LuaFunction callback, boolean outlivesOwner) {
        requireOpen();
        boolean survives = survivesRetirement(outlivesOwner);
        JavaPlugin plugin = MagmaCore.getInstance().getRequestingPlugin();
        int[] taskIdHolder = new int[1];
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (paused) return;
            Bukkit.getScheduler().cancelTask(taskIdHolder[0]);
            ownedTasks.remove(taskIdHolder[0]);
            runOwnedTask(survives, () -> runCallback(callback));
        }, ticks, 1L);
        taskIdHolder[0] = task.getTaskId();
        ownedTasks.put(taskIdHolder[0],
                new OwnedTask(() -> Bukkit.getScheduler().cancelTask(taskIdHolder[0]), survives));
        return taskIdHolder[0];
    }

    private int ownRepeatingTask(int delay, int interval, LuaFunction callback, boolean outlivesOwner) {
        requireOpen();
        boolean survives = survivesRetirement(outlivesOwner);
        JavaPlugin plugin = MagmaCore.getInstance().getRequestingPlugin();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> {
                    if (!paused) runOwnedTask(survives, () -> runCallback(callback));
                },
                delay,
                interval);
        int taskId = task.getTaskId();
        ownedTasks.put(taskId, new OwnedTask(() -> Bukkit.getScheduler().cancelTask(taskId), survives));
        return taskId;
    }

    /**
     * A task outlives the owner when asked to, when it is scheduled from code that does (an
     * owner-marked hook call or a surviving task's body), or when the owner is already retiring.
     */
    private boolean survivesRetirement(boolean outlivesOwner) {
        return outlivesOwner || schedulingSurvivors || retiring;
    }

    /**
     * Runs one owned task body. Tasks it schedules inherit its survival, and once the body
     * returns, a retiring instance whose last surviving task has ended shuts down.
     */
    private void runOwnedTask(boolean survives, Runnable body) {
        boolean previousSchedulingSurvivors = schedulingSurvivors;
        schedulingSurvivors = survives;
        ownedTaskDepth++;
        try {
            body.run();
        } finally {
            ownedTaskDepth--;
            schedulingSurvivors = previousSchedulingSurvivors;
            if (ownedTaskDepth == 0) finishRetirementIfIdle();
        }
    }

    private void finishRetirementIfIdle() {
        if (!retiring || closed || !ownedTasks.isEmpty()) return;
        shutdown();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Script instance is closed");
    }

    private void cancelOwnedTask(int taskId) {
        OwnedTask task = ownedTasks.remove(taskId);
        if (task != null) {
            task.cancel();
        }
        // Inside a task body the check waits until the body returns, so a callback that cancels
        // its own task can still schedule a follow-up.
        if (ownedTaskDepth == 0) finishRetirementIfIdle();
    }

    private void runCallback(LuaFunction callback) {
        if (closed || paused) return;
        try {
            LuaExecutionBudget.run(() -> callback.call(buildContext(null, null, null)));
        } catch (Exception exception) {
            logLuaError("scheduled callback", exception);
            shutdown();
            return;
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
        if (entity.handleScriptError(context, exception)) return;
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

    private enum HookInvocationKind {
        UNHANDLED,
        HANDLED,
        FAILED
    }

    private record HookInvocation(HookInvocationKind kind, LuaValue value) {
        private HookInvocation {
            Objects.requireNonNull(kind, "kind");
            if ((kind == HookInvocationKind.HANDLED) != (value != null)) {
                throw new IllegalArgumentException("Hook invocation result does not match " + kind);
            }
        }

        private static HookInvocation unhandled() {
            return new HookInvocation(HookInvocationKind.UNHANDLED, null);
        }

        private static HookInvocation handled(LuaValue value) {
            return new HookInvocation(HookInvocationKind.HANDLED, Objects.requireNonNull(value, "value"));
        }

        private static HookInvocation failed() {
            return new HookInvocation(HookInvocationKind.FAILED, null);
        }
    }

    private record OwnedTask(Runnable canceller, boolean outlivesOwner) {
        void cancel() {
            canceller.run();
        }
    }
}
