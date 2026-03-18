package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.scripting.tables.LuaTableSupport;
import com.magmaguy.magmacore.scripting.tables.LuaWorldTable;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

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

    private LuaTable scriptTable;
    private Integer tickTaskId = null;
    private boolean closed = false;

    public ScriptInstance(ScriptDefinition definition, ScriptableEntity entity) {
        this.definition = definition;
        this.entity = entity;
    }

    public boolean isClosed() {
        return closed;
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
        try {
            function.checkfunction().call(buildContext(event, directTarget, eventActor));
        } catch (Exception exception) {
            logLuaError(hook.getKey(), exception);
            shutdown();
            return;
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
        if (entity.getBukkitEntity() == null || entity.getBukkitEntity().isDead()) {
            shutdown();
            return;
        }
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
        return switch (key) {
            case "log" -> createLogTable();
            case "scheduler" -> createSchedulerTable();
            case "world" -> {
                Location loc = entity.getLocation();
                yield (loc != null && loc.getWorld() != null)
                        ? LuaWorldTable.build(loc.getWorld())
                        : LuaValue.NIL;
            }
            default -> {
                // Entity's own context table
                if (key.equals(entity.getContextKey())) {
                    yield entity.buildContextTable(this);
                }
                // Delegate to entity for plugin-specific context (event, player, etc.)
                yield entity.resolveExtraContext(key, this);
            }
        };
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
        return scheduler;
    }

    // ── Task management ──────────────────────────────────────────────────

    private void updateTickRegistration() {
        boolean shouldTick = !closed
                && entity.getBukkitEntity() != null
                && !entity.getBukkitEntity().isDead()
                && definition.supportsHook(ScriptHook.ON_TICK);
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
