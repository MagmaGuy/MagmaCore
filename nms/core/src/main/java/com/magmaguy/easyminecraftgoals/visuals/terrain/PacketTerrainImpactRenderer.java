package com.magmaguy.easyminecraftgoals.visuals.terrain;

import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.easyminecraftgoals.internal.PacketEntityInterface;
import com.magmaguy.magmacore.visuals.terrain.TerrainImpactHandle;
import com.magmaguy.magmacore.visuals.terrain.TerrainImpactRenderer;
import com.magmaguy.magmacore.visuals.terrain.TerrainImpactRequest;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Packet-only renderer for the shared cracked-terrain impact. The real world is never mutated:
 * viewers receive temporary air block changes while packet BlockDisplays carry the copied block
 * appearance through a small, deterministic scale-and-tilt animation.
 */
public final class PacketTerrainImpactRenderer implements TerrainImpactRenderer, Listener {
    private static final int VIEWER_RECONCILIATION_INTERVAL = 2;
    private static final int SOURCE_VALIDATION_INTERVAL = 2;
    private static final int MASK_REFRESH_INTERVAL = 10;
    private static final int MAX_ACTIVE_IMPACTS = 32;
    private static final long WARNING_INTERVAL_NANOS = 30_000_000_000L;

    private final Plugin plugin;
    private final NMSAdapter adapter;
    private final TerrainImpactPlanner planner = new TerrainImpactPlanner();
    private final Map<UUID, ActiveImpact> impacts = new LinkedHashMap<>();
    private final Map<UUID, ViewerState> viewerStates = new HashMap<>();
    private final BlockData air;
    private final BukkitTask tickTask;
    private long tick;
    private long nextWarningNanos;
    private boolean closed;

    public PacketTerrainImpactRenderer(Plugin plugin, NMSAdapter adapter) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.air = Material.AIR.createBlockData();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @Override
    public TerrainImpactHandle show(TerrainImpactRequest request) {
        if (closed || request == null || !Bukkit.isPrimaryThread()) return TerrainImpactHandle.inactive();
        Location center = request.center();
        if (center.getWorld() == null) return TerrainImpactHandle.inactive();

        TerrainImpactPlan plan;
        try {
            plan = planner.plan(request);
        } catch (RuntimeException exception) {
            warn("Could not plan a packet terrain impact", exception);
            return TerrainImpactHandle.inactive();
        }
        if (plan.tiles().isEmpty()) return TerrainImpactHandle.inactive();

        while (impacts.size() >= MAX_ACTIVE_IMPACTS) {
            ActiveImpact oldest = impacts.values().iterator().next();
            closeImpact(oldest);
        }

        ActiveImpact impact = new ActiveImpact(request);
        for (TerrainImpactPlan.Tile tile : plan.tiles()) {
            try {
                TileVisual visual = createVisual(center.getWorld(), request, tile);
                impact.tiles.put(visual.key, visual);
            } catch (RuntimeException exception) {
                warn("Skipped one packet terrain tile", exception);
            }
        }
        if (impact.tiles.isEmpty()) return TerrainImpactHandle.inactive();

        impacts.put(impact.id, impact);
        reconcileViewers(impact);
        return impact;
    }

    private TileVisual createVisual(World world, TerrainImpactRequest request, TerrainImpactPlan.Tile tile) {
        BlockKey key = new BlockKey(world.getUID(), tile.x(), tile.y(), tile.z());
        Location location = new Location(world, tile.x(), tile.y(), tile.z());
        PacketEntityInterface packetEntity = adapter.createPacketEntity(EntityType.BLOCK_DISPLAY, location);
        try {
            BlockDisplay display = packetEntity.getBukkitEntity();
            display.setBlock(tile.blockData().clone());
            display.setViewRange((float) request.viewRange());
            display.setShadowRadius(0F);
            display.setShadowStrength(0F);
            display.setDisplayWidth(2F);
            display.setDisplayHeight(2F);
            display.setTeleportDuration(0);
            display.setInterpolationDelay(-1);
            display.setInterpolationDuration(0);
            display.setTransformation(identityTransformation());
            return new TileVisual(
                    key,
                    tile.blockData().clone(),
                    packetEntity,
                    display,
                    toBukkitTransformation(tile.transform()));
        } catch (RuntimeException exception) {
            try {
                packetEntity.remove();
            } catch (RuntimeException ignored) {
                // Preserve the initialization failure that explains why this tile was skipped.
            }
            throw exception;
        }
    }

    private void tick() {
        if (closed) return;
        tick++;
        for (ActiveImpact impact : new ArrayList<>(impacts.values())) {
            if (!impact.active) continue;
            impact.age++;
            if (impact.age == 1) {
                int riseTicks = Math.min(3, Math.max(1, impact.request.durationTicks() / 4));
                applyTransform(impact.tiles.values(), true, riseTicks);
            }

            int settleTicks = Math.min(5, Math.max(1, impact.request.durationTicks() / 3));
            int settleStart = Math.max(1, impact.request.durationTicks() - settleTicks);
            if (!impact.settling && impact.age >= settleStart) {
                impact.settling = true;
                applyTransform(impact.tiles.values(), false, settleTicks);
            }

            if (tick % SOURCE_VALIDATION_INTERVAL == 0) validateSources(impact);
            if (tick % VIEWER_RECONCILIATION_INTERVAL == 0) reconcileViewers(impact);
            if (impact.age >= impact.request.durationTicks()) closeImpact(impact);
        }
        if (tick % MASK_REFRESH_INTERVAL == 0) refreshMasks();
    }

    private void applyTransform(Collection<TileVisual> visuals, boolean impacted, int interpolationTicks) {
        for (TileVisual visual : visuals) {
            try {
                visual.display.setInterpolationDelay(-1);
                visual.display.setInterpolationDuration(interpolationTicks);
                visual.display.setTransformation(impacted ? visual.impactedTransform : identityTransformation());
                visual.packetEntity.syncMetadata();
            } catch (RuntimeException exception) {
                warn("Could not animate one packet terrain tile", exception);
            }
        }
    }

    private void validateSources(ActiveImpact impact) {
        World world = Bukkit.getWorld(impact.worldId);
        if (world == null) {
            closeImpact(impact);
            return;
        }
        List<BlockKey> invalid = new ArrayList<>();
        for (TileVisual visual : impact.tiles.values()) {
            if (!world.isChunkLoaded(visual.key.chunkX(), visual.key.chunkZ())) {
                invalid.add(visual.key);
                continue;
            }
            Block block = world.getBlockAt(visual.key.x, visual.key.y, visual.key.z);
            if (!block.getBlockData().equals(visual.sourceBlockData)) invalid.add(visual.key);
        }
        if (!invalid.isEmpty()) dropTiles(impact, invalid);
    }

    private void dropTiles(ActiveImpact impact, Collection<BlockKey> keys) {
        List<TileVisual> removed = new ArrayList<>();
        for (BlockKey key : keys) {
            TileVisual visual = impact.tiles.remove(key);
            if (visual != null) removed.add(visual);
        }
        if (removed.isEmpty()) return;

        for (UUID viewerId : new LinkedHashSet<>(impact.viewers)) {
            ViewerState state = viewerStates.get(viewerId);
            if (state == null) continue;
            try {
                applyChanges(viewerId, state,
                        state.layers.replace(impact.id, impact.tiles));
            } catch (RuntimeException exception) {
                warn("Could not reconcile a removed packet terrain tile", exception);
                purgeViewer(viewerId);
            }
        }
        removed.forEach(visual -> visual.packetEntity.remove());
        if (impact.tiles.isEmpty()) closeImpact(impact);
    }

    private void reconcileViewers(ActiveImpact impact) {
        World world = Bukkit.getWorld(impact.worldId);
        if (world == null) {
            closeImpact(impact);
            return;
        }

        Set<UUID> candidates = new LinkedHashSet<>(impact.viewers);
        for (Player player : world.getPlayers()) candidates.add(player.getUniqueId());
        for (UUID playerId : candidates) {
            Player player = Bukkit.getPlayer(playerId);
            boolean shouldSee = shouldSee(player, impact);
            boolean currentlySees = impact.viewers.contains(playerId);
            if (shouldSee && !currentlySees) addViewer(impact, player);
            else if (!shouldSee && currentlySees) removeViewer(impact, playerId);
        }
    }

    private boolean shouldSee(Player player, ActiveImpact impact) {
        if (player == null || !player.isOnline() || player.isDead()) return false;
        Location center = impact.request.center();
        if (center.getWorld() == null || !player.getWorld().equals(center.getWorld())) return false;
        return player.getLocation().distanceSquared(center)
                <= impact.request.viewRange() * impact.request.viewRange();
    }

    private void addViewer(ActiveImpact impact, Player player) {
        if (player == null) return;
        UUID viewerId = player.getUniqueId();
        ViewerState state = viewerStates.computeIfAbsent(viewerId, ignored -> new ViewerState());
        List<LayeredTerrainIndex.VisibilityChange<BlockKey, TileVisual>> changes =
                state.layers.replace(impact.id, impact.tiles);
        try {
            applyChanges(viewerId, state, changes);
            impact.viewers.add(viewerId);
        } catch (RuntimeException exception) {
            try {
                applyChanges(viewerId, state, state.layers.remove(impact.id));
            } catch (RuntimeException ignored) {
                purgeViewer(viewerId);
            }
            warn("Could not show a packet terrain impact to one viewer", exception);
        }
    }

    private void removeViewer(ActiveImpact impact, UUID viewerId) {
        impact.viewers.remove(viewerId);
        ViewerState state = viewerStates.get(viewerId);
        if (state == null) return;
        applyChanges(viewerId, state, state.layers.remove(impact.id));
        if (state.layers.isEmpty()) viewerStates.remove(viewerId);
    }

    private void applyChanges(
            UUID viewerId,
            ViewerState state,
            List<LayeredTerrainIndex.VisibilityChange<BlockKey, TileVisual>> changes) {
        Player player = Bukkit.getPlayer(viewerId);
        for (LayeredTerrainIndex.VisibilityChange<BlockKey, TileVisual> change : changes) {
            Optional<TileVisual> before = change.before();
            Optional<TileVisual> after = change.after();
            before.ifPresent(visual -> visual.packetEntity.hideFrom(viewerId));

            if (before.isEmpty() && after.isPresent()) {
                state.masked.add(change.key());
                mask(player, change.key());
            }

            after.ifPresent(visual -> visual.packetEntity.displayTo(viewerId));
            if (after.isEmpty()) {
                state.masked.remove(change.key());
                restore(player, change.key());
            }
        }
    }

    private void refreshMasks() {
        for (Map.Entry<UUID, ViewerState> entry : new ArrayList<>(viewerStates.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                purgeViewer(entry.getKey());
                continue;
            }
            for (BlockKey key : entry.getValue().masked) mask(player, key);
        }
    }

    private void mask(Player player, BlockKey key) {
        if (!canSendBlockChange(player, key)) return;
        World world = player.getWorld();
        player.sendBlockChange(key.location(world), air);
    }

    private void restore(Player player, BlockKey key) {
        if (!canSendBlockChange(player, key)) return;
        World world = player.getWorld();
        Block block = world.getBlockAt(key.x, key.y, key.z);
        player.sendBlockChange(key.location(world), block.getBlockData());
    }

    private static boolean canSendBlockChange(Player player, BlockKey key) {
        if (player == null || !player.isOnline() || !player.getWorld().getUID().equals(key.worldId)) return false;
        return player.getWorld().isChunkLoaded(key.chunkX(), key.chunkZ());
    }

    private void closeImpact(ActiveImpact impact) {
        if (impact == null || !impact.active) return;
        impact.active = false;
        impacts.remove(impact.id);
        for (UUID viewerId : new LinkedHashSet<>(impact.viewers)) {
            try {
                removeViewer(impact, viewerId);
            } catch (RuntimeException exception) {
                warn("Could not reconcile a closing packet terrain impact", exception);
                purgeViewer(viewerId);
            }
        }
        for (TileVisual visual : impact.tiles.values()) {
            try {
                visual.packetEntity.remove();
            } catch (RuntimeException exception) {
                warn("Could not remove one packet terrain tile", exception);
            }
        }
        impact.tiles.clear();
    }

    private void purgeViewer(UUID viewerId) {
        ViewerState state = viewerStates.remove(viewerId);
        if (state == null) return;
        for (ActiveImpact impact : impacts.values()) impact.viewers.remove(viewerId);
        for (TileVisual visual : allVisuals()) {
            try {
                visual.packetEntity.hideFrom(viewerId);
            } catch (RuntimeException exception) {
                warn("Could not hide one packet terrain tile", exception);
            }
        }
        Player player = Bukkit.getPlayer(viewerId);
        for (BlockKey key : state.masked) restore(player, key);
        state.masked.clear();
    }

    private List<TileVisual> allVisuals() {
        List<TileVisual> visuals = new ArrayList<>();
        for (ActiveImpact impact : impacts.values()) visuals.addAll(impact.tiles.values());
        return visuals;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        purgeViewer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        purgeViewer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        purgeViewer(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        purgeViewer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        UUID worldId = event.getWorld().getUID();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        for (ActiveImpact impact : new ArrayList<>(impacts.values())) {
            if (!impact.worldId.equals(worldId)) continue;
            List<BlockKey> affected = impact.tiles.keySet().stream()
                    .filter(key -> key.chunkX() == chunkX && key.chunkZ() == chunkZ)
                    .toList();
            if (!affected.isEmpty()) dropTiles(impact, affected);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID worldId = event.getWorld().getUID();
        for (ActiveImpact impact : new ArrayList<>(impacts.values())) {
            if (impact.worldId.equals(worldId)) closeImpact(impact);
        }
    }

    @Override
    public void close() {
        if (!Bukkit.isPrimaryThread() && plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, this::close);
            return;
        }
        if (closed) return;
        closed = true;
        tickTask.cancel();
        for (ActiveImpact impact : new ArrayList<>(impacts.values())) closeImpact(impact);
        for (UUID viewerId : new ArrayList<>(viewerStates.keySet())) purgeViewer(viewerId);
        HandlerList.unregisterAll(this);
    }

    private void warn(String message, RuntimeException exception) {
        long now = System.nanoTime();
        if (now < nextWarningNanos) return;
        nextWarningNanos = now + WARNING_INTERVAL_NANOS;
        plugin.getLogger().warning(message + ": " + exception.getClass().getSimpleName()
                + (exception.getMessage() == null ? "" : " - " + exception.getMessage()));
    }

    private static Transformation toBukkitTransformation(TerrainImpactPlan.Transform transform) {
        return new Transformation(
                new Vector3f(
                        (float) transform.translationX(),
                        (float) transform.translationY(),
                        (float) transform.translationZ()),
                new Quaternionf(
                        (float) transform.rotationX(),
                        (float) transform.rotationY(),
                        (float) transform.rotationZ(),
                        (float) transform.rotationW()),
                new Vector3f(
                        (float) transform.uniformScale(),
                        (float) transform.uniformScale(),
                        (float) transform.uniformScale()),
                new Quaternionf());
    }

    private static Transformation identityTransformation() {
        return new Transformation(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(1F, 1F, 1F),
                new Quaternionf());
    }

    private final class ActiveImpact implements TerrainImpactHandle {
        private final UUID id = UUID.randomUUID();
        private final TerrainImpactRequest request;
        private final UUID worldId;
        private final Map<BlockKey, TileVisual> tiles = new LinkedHashMap<>();
        private final Set<UUID> viewers = new LinkedHashSet<>();
        private int age;
        private boolean settling;
        private boolean active = true;

        private ActiveImpact(TerrainImpactRequest request) {
            this.request = request;
            this.worldId = Objects.requireNonNull(request.center().getWorld()).getUID();
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public void close() {
            if (!active) return;
            if (!Bukkit.isPrimaryThread() && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> closeImpact(this));
                return;
            }
            closeImpact(this);
        }
    }

    private static final class ViewerState {
        private final LayeredTerrainIndex<BlockKey, TileVisual> layers = new LayeredTerrainIndex<>();
        private final Set<BlockKey> masked = new LinkedHashSet<>();
    }

    private static final class TileVisual {
        private final BlockKey key;
        private final BlockData sourceBlockData;
        private final PacketEntityInterface packetEntity;
        private final BlockDisplay display;
        private final Transformation impactedTransform;

        private TileVisual(
                BlockKey key,
                BlockData sourceBlockData,
                PacketEntityInterface packetEntity,
                BlockDisplay display,
                Transformation impactedTransform) {
            this.key = key;
            this.sourceBlockData = sourceBlockData;
            this.packetEntity = packetEntity;
            this.display = display;
            this.impactedTransform = impactedTransform;
        }
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        private int chunkX() {
            return x >> 4;
        }

        private int chunkZ() {
            return z >> 4;
        }

        private Location location(World world) {
            return new Location(world, x, y, z);
        }
    }
}
