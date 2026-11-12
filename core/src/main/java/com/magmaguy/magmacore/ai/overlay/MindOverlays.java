package com.magmaguy.magmacore.ai.overlay;

import com.magmaguy.magmacore.ai.MindHandle;
import com.magmaguy.magmacore.ai.MindHost;
import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.scripting.tables.LuaEntityTable;
import com.magmaguy.magmacore.scripting.tables.LuaTableSupport;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Lays Lua behavior modules over the AI of mobs a plugin did not make, such as naturally spawned creepers, for a
 * time or until cleared, and gives each mob its own AI back afterwards.
 *
 * <p>A plugin makes one of these beside its {@link MindHost}, which does the driving (see
 * {@link MindHost#borrowBody}); this adds what a plugin would otherwise write itself. It reads the modules from a
 * folder of {@code .lua} files, each an {@code ai.module} as a Mind program takes them. It composes the modules an
 * overlay names into one program and runs it on the mob. It keeps count of the overlay's time, remembers who hurt
 * whom so a module can defend the mob's owner, and stops the mob hurting that owner. It writes the overlay on the
 * mob, so the mob has it again when its chunk loads or the server restarts. And it gives Lua scripts of the same
 * plugin {@code override_ai} and {@code restore_ai} on every mob they handle.</p>
 *
 * <p>The plugin calls {@link #load} once its host exists, {@link #tick} every tick after {@link MindHost#tick}, and
 * {@link #close} before it shuts the host down. Everything here runs on the server thread.</p>
 */
public final class MindOverlays implements Listener, AutoCloseable {
    /** How long a hit is remembered, in ticks: ten seconds, the time a vanilla mob stays angry at its attacker. */
    static final int HIT_MEMORY_TICKS = 200;
    /**
     * The furthest a module may search for another entity. The search walks every entity section in the box in
     * Java, where the Lua budget cannot interrupt it, so it needs a bound of its own: this is the distance from a
     * player past which Minecraft itself removes hostile mobs.
     */
    static final double MAX_SEARCH_RADIUS = 128.0D;

    // The service Lua scripts of this plugin reach through their entity tables; entity tables are built by static
    // code, and a reload makes a new service.
    private static final AtomicReference<MindOverlays> LUA_SERVICE = new AtomicReference<>();
    private static boolean luaExposed;

    private final Plugin plugin;
    private final MindHost host;
    private final String namespace;
    private final Path folder;
    private final Map<String, String> bundled;
    private final Consumer<String> warn;
    private final NamespacedKey recordKey;
    private final Map<UUID, Overlay> overlays = new LinkedHashMap<>();
    // How many overlays each owner has, so a hit is looked up only when it could matter.
    private final Map<UUID, Integer> owners = new HashMap<>();
    private final Map<UUID, Hit> lastAttacker = new HashMap<>();
    private final Map<UUID, Hit> lastVictim = new HashMap<>();
    private MindOverlayCatalog catalog;
    private long tick;
    private boolean closed;

    /**
     * @param plugin    the plugin that owns the host
     * @param host      the Mind host that drives the mobs
     * @param namespace the module namespace: a module file {@code guard.lua} declares id {@code <namespace>:mob/guard}
     * @param folder    where the module files are
     * @param bundled   module sources by name, written into the folder when it has no file of that name
     * @param warn      told about files that do not load and overlays that cannot resume
     */
    public MindOverlays(
            Plugin plugin,
            MindHost host,
            String namespace,
            Path folder,
            Map<String, String> bundled,
            Consumer<String> warn) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.host = Objects.requireNonNull(host, "host");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.folder = Objects.requireNonNull(folder, "folder");
        this.bundled = Map.copyOf(Objects.requireNonNull(bundled, "bundled"));
        this.warn = Objects.requireNonNull(warn, "warn");
        recordKey = new NamespacedKey(plugin, "mob_behaviors");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Reads the folder again, writing any bundled module it lacks first. Running overlays stop and start again
     * from the new files, and every loaded mob that has an overlay written on it gets it back.
     */
    public void load() {
        requireOpen();
        for (Overlay overlay : new ArrayList<>(overlays.values())) end(overlay, false);
        if (catalog != null) catalog.close();
        writeBundled();
        catalog = MindOverlayCatalog.read(namespace, folder, warn);
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity living : world.getLivingEntities()) resume(living);
        }
    }

    private void writeBundled() {
        try {
            Files.createDirectories(folder);
            for (Map.Entry<String, String> module : bundled.entrySet()) {
                Path file = folder.resolve(module.getKey() + ".lua");
                if (Files.notExists(file)) Files.writeString(file, module.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            warn.accept("Could not write the default mob behaviors into " + folder + ": " + exception.getMessage());
        }
    }

    /** The names of the behaviors that loaded, in alphabetical order. */
    public List<String> behaviors() {
        return catalog == null ? List.of() : catalog.behaviors();
    }

    /**
     * Lays the request's behaviors over the mob's own AI, in place of any overlay it had.
     *
     * @return null when the mob now runs them, otherwise why it does not; the mob is then as it was
     */
    public String apply(LivingEntity mob, MindOverlayRequest request) {
        requireOpen();
        Objects.requireNonNull(mob, "mob");
        Objects.requireNonNull(request, "request");
        long endsAt = request.durationTicks() > 0L ? mob.getWorld().getGameTime() + request.durationTicks() : 0L;
        String problem = start(mob, new MindOverlayRecord(request, endsAt));
        if (problem == null) {
            mob.getPersistentDataContainer().set(recordKey, PersistentDataType.STRING,
                    new MindOverlayRecord(request, endsAt).encode());
        }
        return problem;
    }

    private String start(LivingEntity mob, MindOverlayRecord record) {
        if (catalog == null) return "The mob behaviors have not loaded yet";
        MindOverlayRequest request = record.request();
        MindOverlayCatalog.Program program;
        try {
            program = catalog.program(request.behaviors());
        } catch (IllegalArgumentException exception) {
            return exception.getMessage();
        }
        Overlay previous = overlays.get(mob.getUniqueId());
        if (previous != null) end(previous, false);
        if (!host.canBorrow(mob)) return "This entity's AI cannot be overridden: only a living mob no other Mind drives";

        Overlay overlay = new Overlay(mob, request, record.endsAt());
        Map<String, Map<String, Object>> settings = MindOverlaySettings.merge(
                program.modules(), program.idPrefix(), request.settings(), warn);
        MindHandle handle = null;
        try {
            MindProgram instance = program.definition().instantiate(new MindOverlayLua(this, overlay, settings));
            handle = host.open(UUID.randomUUID(), instance);
            handle.attachBody(host.borrowBody(mob));
        } catch (RuntimeException exception) {
            if (handle != null) handle.close();
            return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
        overlay.handle = handle;
        overlays.put(mob.getUniqueId(), overlay);
        if (request.owner() != null) owners.merge(request.owner(), 1, Integer::sum);
        return null;
    }

    /**
     * Gives the mob its own AI back and forgets its overlay.
     *
     * @return whether it had one
     */
    public boolean clear(LivingEntity mob) {
        requireOpen();
        Objects.requireNonNull(mob, "mob");
        boolean written = mob.getPersistentDataContainer().has(recordKey, PersistentDataType.STRING);
        mob.getPersistentDataContainer().remove(recordKey);
        Overlay overlay = overlays.get(mob.getUniqueId());
        if (overlay != null) end(overlay, false);
        return overlay != null || written;
    }

    /** Clears every running overlay, and returns how many there were. */
    public int clearAll() {
        requireOpen();
        List<Overlay> running = new ArrayList<>(overlays.values());
        for (Overlay overlay : running) end(overlay, true);
        return running.size();
    }

    /** Whether the entity runs an overlay now. */
    public boolean overlaid(Entity entity) {
        return overlays.containsKey(entity.getUniqueId());
    }

    /** What the entity's overlay was asked to be, when it runs one. */
    public Optional<MindOverlayRequest> overlay(Entity entity) {
        Overlay overlay = overlays.get(entity.getUniqueId());
        return overlay == null ? Optional.empty() : Optional.of(overlay.request);
    }

    /** The mobs that run an overlay now. */
    public List<LivingEntity> overlaidMobs() {
        List<LivingEntity> mobs = new ArrayList<>(overlays.size());
        for (Overlay overlay : overlays.values()) mobs.add(overlay.mob);
        return mobs;
    }

    /** Whether the overlay's mob runs the behavior with this id now; see {@link MindHandle#isRunning}. */
    public boolean running(Entity entity, String behaviorIdentifier) {
        Overlay overlay = overlays.get(entity.getUniqueId());
        return overlay != null && overlay.handle.isRunning(behaviorIdentifier);
    }

    /** Ends the overlays whose time is up or whose mob is gone. Call every tick, after {@link MindHost#tick}. */
    public void tick() {
        if (closed) return;
        tick++;
        if (overlays.isEmpty()) {
            if (!lastAttacker.isEmpty()) lastAttacker.clear();
            if (!lastVictim.isEmpty()) lastVictim.clear();
            return;
        }
        for (Overlay overlay : new ArrayList<>(overlays.values())) {
            // The host hands a mob back by itself when it dies, unloads or leaves its world; what is written on
            // it stays, so a mob that unloaded has its overlay again when it loads.
            if (!overlay.handle.hasBody()) {
                end(overlay, false);
                // A mob that changed dimension is a copy of itself there, with its id and what is written on
                // it, so it carries on in the new world rather than waiting for its chunk to load again.
                if (Bukkit.getEntity(overlay.mob.getUniqueId()) instanceof LivingEntity successor
                        && successor != overlay.mob && successor.isValid()) {
                    resume(successor);
                }
            } else if (overlay.finished
                    || (overlay.endsAt > 0L && overlay.mob.getWorld().getGameTime() >= overlay.endsAt)) {
                end(overlay, true);
            }
        }
        if (tick % HIT_MEMORY_TICKS == 0L) {
            lastAttacker.values().removeIf(hit -> tick - hit.tick > HIT_MEMORY_TICKS);
            lastVictim.values().removeIf(hit -> tick - hit.tick > HIT_MEMORY_TICKS);
        }
    }

    private void end(Overlay overlay, boolean forget) {
        if (!overlays.remove(overlay.mob.getUniqueId(), overlay)) return;
        UUID owner = overlay.request.owner();
        if (owner != null) owners.computeIfPresent(owner, (id, count) -> count > 1 ? count - 1 : null);
        lastAttacker.remove(overlay.mob.getUniqueId());
        overlay.handle.close();
        if (forget) overlay.mob.getPersistentDataContainer().remove(recordKey);
    }

    /** Starts the overlay written on a mob that just loaded, unless its time ran out while it was away. */
    private void resume(LivingEntity mob) {
        String written = mob.getPersistentDataContainer().get(recordKey, PersistentDataType.STRING);
        if (written == null || overlays.containsKey(mob.getUniqueId())) return;
        Optional<MindOverlayRecord> record = MindOverlayRecord.decode(written);
        if (record.isEmpty() || (record.get().endsAt() > 0L && mob.getWorld().getGameTime() >= record.get().endsAt())) {
            mob.getPersistentDataContainer().remove(recordKey);
            return;
        }
        String problem = start(mob, record.get());
        if (problem != null) {
            warn.accept("A " + mob.getType().name().toLowerCase(Locale.ROOT) + " could not take up its mob behaviors "
                    + record.get().request().behaviors() + " again: " + problem);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (closed || catalog == null) return;
        List<LivingEntity> written = new ArrayList<>();
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity living
                    && living.getPersistentDataContainer().has(recordKey, PersistentDataType.STRING)) {
                written.add(living);
            }
        }
        if (written.isEmpty()) return;
        // A tick later the mobs are in their world for certain, which a borrow asks for.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (closed || catalog == null) return;
            for (LivingEntity living : written) if (living.isValid()) resume(living);
        });
    }

    /** An overlaid mob never hurts the one it serves, whatever its module does: a creeper's blast included. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOwnerHurt(EntityDamageByEntityEvent event) {
        if (overlays.isEmpty()) return;
        Entity attacker = attacker(event.getDamager());
        if (attacker == null) return;
        Overlay overlay = overlays.get(attacker.getUniqueId());
        if (overlay != null && event.getEntity().getUniqueId().equals(overlay.request.owner())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (overlays.isEmpty() || !(event.getEntity() instanceof LivingEntity victim)) return;
        if (!(attacker(event.getDamager()) instanceof LivingEntity attacker) || attacker == victim) return;
        UUID victimId = victim.getUniqueId(), attackerId = attacker.getUniqueId();
        if (overlays.containsKey(victimId) || owners.containsKey(victimId)) {
            lastAttacker.put(victimId, new Hit(attackerId, tick));
        }
        // An owner hitting one of its own mobs has not picked it as a target for the others.
        Overlay hurt = overlays.get(victimId);
        if (owners.containsKey(attackerId) && (hurt == null || !attackerId.equals(hurt.request.owner()))) {
            lastVictim.put(attackerId, new Hit(victimId, tick));
        }
    }

    /** Whoever is behind a hit: the shooter of a projectile, else the entity itself. */
    private static Entity attacker(Entity damager) {
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) return shooter;
        return damager;
    }

    LivingEntity owner(Overlay overlay) {
        return overlay.request.owner() == null ? null : present(overlay, overlay.request.owner());
    }

    /** Whoever hurt the mob's owner within the hit memory, while still alive in the mob's world. */
    LivingEntity ownerAttacker(Overlay overlay) {
        return overlay.request.owner() == null ? null : remembered(overlay, lastAttacker.get(overlay.request.owner()));
    }

    /** Whoever the mob's owner hurt within the hit memory, while still alive in the mob's world. */
    LivingEntity ownerTarget(Overlay overlay) {
        return overlay.request.owner() == null ? null : remembered(overlay, lastVictim.get(overlay.request.owner()));
    }

    /** Whoever hurt the mob within the hit memory, while still alive in its world. */
    LivingEntity attacker(Overlay overlay) {
        return remembered(overlay, lastAttacker.get(overlay.mob.getUniqueId()));
    }

    /** The other side of a hit that is still remembered, unless it is the mob's owner or serves the same owner. */
    private LivingEntity remembered(Overlay overlay, Hit hit) {
        if (hit == null || tick - hit.tick > HIT_MEMORY_TICKS || ally(overlay, hit.other)) return null;
        return present(overlay, hit.other);
    }

    private boolean ally(Overlay overlay, UUID id) {
        UUID owner = overlay.request.owner();
        if (owner == null) return false;
        if (owner.equals(id)) return true;
        Overlay other = overlays.get(id);
        return other != null && owner.equals(other.request.owner());
    }

    /** The living entity with this id, while it is alive in the mob's world. */
    LivingEntity present(Overlay overlay, UUID id) {
        return Bukkit.getEntity(id) instanceof LivingEntity living && living.isValid() && !living.isDead()
                && living.getWorld() == overlay.mob.getWorld() ? living : null;
    }

    /**
     * The nearest living entity of a kind within the radius: {@code player}, {@code hostile}, {@code passive},
     * {@code living}, or an entity type's name. Never the mob itself, its owner, a mob that serves the same owner,
     * a player in creative or spectator mode, or an armor stand.
     */
    LivingEntity nearest(Overlay overlay, double radius, String kind) {
        if (!(radius > 0.0D)) return null;
        double reach = Math.min(radius, MAX_SEARCH_RADIUS);
        String wanted = kind == null ? "living" : kind.toLowerCase(Locale.ROOT);
        LivingEntity nearest = null;
        double nearestSquared = reach * reach;
        org.bukkit.Location from = overlay.mob.getLocation();
        for (Entity entity : overlay.mob.getNearbyEntities(reach, reach, reach)) {
            if (!(entity instanceof LivingEntity living) || living.isDead() || living instanceof ArmorStand) continue;
            if (!isKind(living, wanted)) continue;
            if (living instanceof Player player && player.getGameMode() != GameMode.SURVIVAL
                    && player.getGameMode() != GameMode.ADVENTURE) continue;
            if (ally(overlay, living.getUniqueId())) continue;
            double distanceSquared = living.getLocation().distanceSquared(from);
            if (distanceSquared > nearestSquared) continue;
            nearest = living;
            nearestSquared = distanceSquared;
        }
        return nearest;
    }

    private static boolean isKind(LivingEntity living, String kind) {
        return switch (kind) {
            case "living", "any" -> true;
            case "player" -> living instanceof Player;
            case "hostile" -> living instanceof Enemy;
            case "passive" -> living instanceof Animals;
            default -> living.getType().name().equalsIgnoreCase(kind);
        };
    }

    Long remaining(Overlay overlay) {
        return overlay.endsAt <= 0L ? null : Math.max(0L, overlay.endsAt - overlay.mob.getWorld().getGameTime());
    }

    /**
     * Gives the Lua scripts this plugin runs {@code override_ai(behaviors, options)}, {@code restore_ai()} and
     * {@code ai_overridden} on the entity table of every mob that is not a player.
     *
     * <p>{@code behaviors} is one name or a list of names. {@code options} may hold {@code duration} in ticks,
     * {@code owner} as an entity table or uuid, and {@code settings} as a table of tables per behavior name.
     * {@code override_ai} returns true, or false and the reason.</p>
     */
    public void exposeToLua() {
        requireOpen();
        LUA_SERVICE.set(this);
        synchronized (MindOverlays.class) {
            if (luaExposed) return;
            luaExposed = true;
        }
        LuaEntityTable.registerEnricher(MindOverlays::enrich);
    }

    private static void enrich(LuaTable table, Entity entity) {
        if (!(entity instanceof LivingEntity mob) || entity instanceof Player) return;
        LuaTableSupport.lazyField(table, "ai_overridden", () -> {
            MindOverlays service = LUA_SERVICE.get();
            return LuaValue.valueOf(service != null && service.overlaid(mob));
        });
        LuaTableSupport.lazyField(table, "override_ai", () -> LuaTableSupport.tableMethod(table, args -> {
            MindOverlays service = LUA_SERVICE.get();
            if (service == null) return LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf("Mob behaviors are off"));
            String problem = service.apply(mob, MindOverlayLua.request(args));
            return problem == null ? LuaValue.TRUE : LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(problem));
        }));
        LuaTableSupport.lazyField(table, "restore_ai", () -> LuaTableSupport.tableMethod(table, args -> {
            MindOverlays service = LUA_SERVICE.get();
            return LuaValue.valueOf(service != null && service.clear(mob));
        }));
    }

    /** Gives every mob its own AI back, leaving what is written on each so it resumes when the plugin is back. */
    @Override
    public void close() {
        if (closed) return;
        for (Overlay overlay : new ArrayList<>(overlays.values())) end(overlay, false);
        closed = true;
        LUA_SERVICE.compareAndSet(this, null);
        HandlerList.unregisterAll(this);
        if (catalog != null) catalog.close();
        catalog = null;
        owners.clear();
        lastAttacker.clear();
        lastVictim.clear();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("The mob behaviors are closed");
    }

    /** One mob's overlay while it runs. */
    static final class Overlay {
        final LivingEntity mob;
        final MindOverlayRequest request;
        final long endsAt;
        MindHandle handle;
        // A module asked for the overlay to end; it does once the tick is over.
        boolean finished;

        private Overlay(LivingEntity mob, MindOverlayRequest request, long endsAt) {
            this.mob = mob;
            this.request = request;
            this.endsAt = endsAt;
        }
    }

    private record Hit(UUID other, long tick) {
    }
}
