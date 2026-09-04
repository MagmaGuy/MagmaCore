package com.magmaguy.easyminecraftgoals.internal.pathfinding;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.util.Workload;
import com.magmaguy.magmacore.util.WorkloadRunnable;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Flying;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.WaterMob;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Captures loaded chunks within a global sync workload, then performs bounded A* off-thread.
 * Planning is deliberately rolling: very long destinations are solved one loaded horizon at a time.
 */
final class LongRangePathPlanner {
    static final double NATIVE_LEG_DISTANCE = 8D;
    static final double PLANNING_HORIZON = 96D;

    private static final double CORRIDOR_RADIUS = 48D;
    private static final int MAX_CAPTURED_CHUNKS = 160;
    private static final int MAX_VISITED_NODES = 180_000;

    private LongRangePathPlanner() {
    }

    static Request plan(LivingEntity entity, Location finalTarget, Consumer<PlanResult> callback) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(finalTarget, "finalTarget");
        Objects.requireNonNull(callback, "callback");
        Location start = entity.getLocation().clone();
        if (start.getWorld() == null || start.getWorld() != finalTarget.getWorld()) {
            callback.accept(PlanResult.noPath(false));
            return Request.cancelled();
        }

        boolean reachesFinal = start.distance(finalTarget) <= PLANNING_HORIZON;
        Location planningTarget = reachesFinal ? finalTarget.clone() : horizonTarget(start, finalTarget);
        SnapshotPathSolver.BodyProfile body = bodyProfile(entity);
        Request request = new Request(
                start,
                planningTarget,
                finalTarget.clone(),
                reachesFinal,
                body,
                callback);
        request.start();
        return request;
    }

    private static Location horizonTarget(Location start, Location finalTarget) {
        Vector delta = finalTarget.toVector().subtract(start.toVector());
        if (delta.lengthSquared() <= 1.0E-8D) return finalTarget.clone();
        return start.clone().add(delta.normalize().multiply(PLANNING_HORIZON));
    }

    private static SnapshotPathSolver.BodyProfile bodyProfile(LivingEntity entity) {
        SnapshotPathSolver.MovementMode mode = entity instanceof WaterMob
                ? SnapshotPathSolver.MovementMode.AQUATIC
                : entity instanceof Flying
                ? SnapshotPathSolver.MovementMode.FLYING
                : SnapshotPathSolver.MovementMode.GROUND;
        BoundingBox bounds = entity.getBoundingBox();
        double width = Math.max(bounds.getWidthX(), bounds.getWidthZ());
        int horizontalRadius = Math.max(0, (int) Math.ceil((width - 1D) / 2D));
        int heightBlocks = Math.max(1, (int) Math.ceil(bounds.getHeight()));
        return new SnapshotPathSolver.BodyProfile(mode, horizontalRadius, heightBlocks);
    }

    private static List<ChunkCoordinate> corridorChunks(Location start, Location target) {
        int minimumChunkX = ((int) Math.floor(Math.min(start.getX(), target.getX()) - CORRIDOR_RADIUS)) >> 4;
        int maximumChunkX = ((int) Math.floor(Math.max(start.getX(), target.getX()) + CORRIDOR_RADIUS)) >> 4;
        int minimumChunkZ = ((int) Math.floor(Math.min(start.getZ(), target.getZ()) - CORRIDOR_RADIUS)) >> 4;
        int maximumChunkZ = ((int) Math.floor(Math.max(start.getZ(), target.getZ()) + CORRIDOR_RADIUS)) >> 4;
        double acceptedRadius = CORRIDOR_RADIUS + 12D;
        double acceptedRadiusSquared = acceptedRadius * acceptedRadius;

        List<ChunkCoordinate> chunks = new ArrayList<>();
        for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                double centerX = chunkX * 16D + 8D;
                double centerZ = chunkZ * 16D + 8D;
                if (distanceSquaredToSegment(
                        centerX,
                        centerZ,
                        start.getX(),
                        start.getZ(),
                        target.getX(),
                        target.getZ()) <= acceptedRadiusSquared) {
                    chunks.add(new ChunkCoordinate(chunkX, chunkZ));
                }
            }
        }
        chunks.sort(Comparator.comparingDouble(chunk -> chunk.distanceSquared(start.getX(), start.getZ())));
        return chunks.size() <= MAX_CAPTURED_CHUNKS
                ? List.copyOf(chunks)
                : List.copyOf(chunks.subList(0, MAX_CAPTURED_CHUNKS));
    }

    private static double distanceSquaredToSegment(
            double pointX,
            double pointZ,
            double startX,
            double startZ,
            double endX,
            double endZ) {
        double dx = endX - startX;
        double dz = endZ - startZ;
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared <= 1.0E-8D) {
            double offsetX = pointX - startX;
            double offsetZ = pointZ - startZ;
            return offsetX * offsetX + offsetZ * offsetZ;
        }
        double fraction = ((pointX - startX) * dx + (pointZ - startZ) * dz) / lengthSquared;
        fraction = Math.max(0D, Math.min(1D, fraction));
        double closestX = startX + fraction * dx;
        double closestZ = startZ + fraction * dz;
        double offsetX = pointX - closestX;
        double offsetZ = pointZ - closestZ;
        return offsetX * offsetX + offsetZ * offsetZ;
    }

    private static List<SnapshotPathSolver.Point> navigationWaypoints(List<SnapshotPathSolver.Point> path) {
        if (path.size() <= 1) return List.of();
        List<SnapshotPathSolver.Point> waypoints = new ArrayList<>();
        SnapshotPathSolver.Point previous = path.getFirst();
        double accumulated = 0D;
        int previousDx = 0;
        int previousDz = 0;
        for (int index = 1; index < path.size(); index++) {
            SnapshotPathSolver.Point current = path.get(index);
            int dx = Integer.compare(current.x(), previous.x());
            int dz = Integer.compare(current.z(), previous.z());
            accumulated += distance(previous, current);
            boolean sharpTurn = index > 1 && (dx != previousDx || dz != previousDz)
                    && dx * previousDx + dz * previousDz <= 0;
            if (accumulated >= NATIVE_LEG_DISTANCE || sharpTurn || index == path.size() - 1) {
                if (waypoints.isEmpty() || !waypoints.getLast().equals(current)) waypoints.add(current);
                accumulated = 0D;
            }
            previousDx = dx;
            previousDz = dz;
            previous = current;
        }
        return List.copyOf(waypoints);
    }

    private static double distance(SnapshotPathSolver.Point first, SnapshotPathSolver.Point second) {
        int dx = second.x() - first.x();
        int dy = second.y() - first.y();
        int dz = second.z() - first.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static Location location(World world, SnapshotPathSolver.Point point) {
        return new Location(world, point.x() + 0.5D, point.y(), point.z() + 0.5D);
    }

    static final class Request implements AutoCloseable, Workload {
        private final Location start;
        private final Location planningTarget;
        private final Location finalTarget;
        private final boolean reachesFinal;
        private final SnapshotPathSolver.BodyProfile body;
        private final Consumer<PlanResult> callback;
        private final List<ChunkCoordinate> chunks;
        private final int minimumY;
        private final int maximumY;
        private final Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private int nextChunk;
        private boolean missingTerrain;

        private Request(
                Location start,
                Location planningTarget,
                Location finalTarget,
                boolean reachesFinal,
                SnapshotPathSolver.BodyProfile body,
                Consumer<PlanResult> callback) {
            this.start = start;
            this.planningTarget = planningTarget;
            this.finalTarget = finalTarget;
            this.reachesFinal = reachesFinal;
            this.body = body;
            this.callback = callback;
            this.chunks = corridorChunks(start, planningTarget);
            World world = start.getWorld();
            this.minimumY = world == null ? -64 : world.getMinHeight();
            this.maximumY = world == null ? 320 : world.getMaxHeight();
        }

        private static Request cancelled() {
            Request request = new Request(
                    new Location(null, 0D, 0D, 0D),
                    new Location(null, 0D, 0D, 0D),
                    new Location(null, 0D, 0D, 0D),
                    true,
                    new SnapshotPathSolver.BodyProfile(SnapshotPathSolver.MovementMode.GROUND, 0, 1),
                    ignored -> { });
            request.cancelled.set(true);
            return request;
        }

        private void start() {
            if (chunks.isEmpty()) {
                dispatchSearch();
                return;
            }
            SnapshotWorkQueue.enqueue(this);
        }

        @Override
        public void compute() {
            if (cancelled.get()) return;
            World world = start.getWorld();
            if (world == null || nextChunk >= chunks.size()) {
                dispatchSearch();
                return;
            }

            ChunkCoordinate coordinate = chunks.get(nextChunk++);
            if (!world.isChunkLoaded(coordinate.x(), coordinate.z())) {
                missingTerrain = true;
            } else {
                Chunk chunk = world.getChunkAt(coordinate.x(), coordinate.z());
                snapshots.put(coordinate.key(), chunk.getChunkSnapshot(false, false, false));
            }

            if (nextChunk < chunks.size()) SnapshotWorkQueue.enqueue(this);
            else dispatchSearch();
        }

        private void dispatchSearch() {
            if (cancelled.get()) return;
            Plugin plugin = MagmaCore.getInstance().getRequestingPlugin();
            AsyncSearchQueue.submit(plugin, () -> {
                if (cancelled.get()) return;
                PlanResult result = search();
                if (cancelled.get()) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!cancelled.get()) callback.accept(result);
                });
            });
        }

        private PlanResult search() {
            World world = start.getWorld();
            if (world == null) return PlanResult.noPath(false);
            SnapshotTerrain terrain = new SnapshotTerrain(
                    snapshots,
                    minimumY,
                    maximumY);
            SnapshotPathSolver.Result solved = SnapshotPathSolver.solve(
                    terrain,
                    new SnapshotPathSolver.Point(start.getBlockX(), start.getBlockY(), start.getBlockZ()),
                    new SnapshotPathSolver.Point(
                            planningTarget.getBlockX(),
                            planningTarget.getBlockY(),
                            planningTarget.getBlockZ()),
                    body,
                    MAX_VISITED_NODES);
            if (!solved.found()) {
                return missingTerrain
                        ? PlanResult.terrainUnavailable(solved.exhaustedNodeBudget())
                        : PlanResult.noPath(solved.exhaustedNodeBudget());
            }

            List<Location> preview = solved.points().stream().map(point -> location(world, point)).toList();
            List<Location> waypoints = new ArrayList<>(
                    navigationWaypoints(solved.points()).stream().map(point -> location(world, point)).toList());
            if (reachesFinal) {
                if (waypoints.isEmpty() || waypoints.getLast().distanceSquared(finalTarget) > 1.0E-8D) {
                    waypoints.add(finalTarget.clone());
                } else {
                    waypoints.set(waypoints.size() - 1, finalTarget.clone());
                }
                if (preview.isEmpty() || preview.getLast().distanceSquared(finalTarget) > 1.0E-8D) {
                    preview = new ArrayList<>(preview);
                    preview.add(finalTarget.clone());
                }
            }
            return PlanResult.found(waypoints, preview, reachesFinal, solved.exhaustedNodeBudget());
        }

        @Override
        public void close() {
            cancelled.set(true);
        }
    }

    enum Outcome {
        FOUND,
        NO_PATH,
        TERRAIN_UNAVAILABLE
    }

    record PlanResult(
            Outcome outcome,
            List<Location> waypoints,
            List<Location> preview,
            boolean reachesFinal,
            boolean exhaustedNodeBudget) {
        PlanResult {
            waypoints = cloneLocations(waypoints);
            preview = cloneLocations(preview);
        }

        static PlanResult found(
                List<Location> waypoints,
                List<Location> preview,
                boolean reachesFinal,
                boolean exhaustedNodeBudget) {
            return new PlanResult(Outcome.FOUND, waypoints, preview, reachesFinal, exhaustedNodeBudget);
        }

        static PlanResult noPath(boolean exhaustedNodeBudget) {
            return new PlanResult(Outcome.NO_PATH, List.of(), List.of(), false, exhaustedNodeBudget);
        }

        static PlanResult terrainUnavailable(boolean exhaustedNodeBudget) {
            return new PlanResult(
                    Outcome.TERRAIN_UNAVAILABLE,
                    List.of(),
                    List.of(),
                    false,
                    exhaustedNodeBudget);
        }

        private static List<Location> cloneLocations(List<Location> locations) {
            return locations.stream().map(Location::clone).toList();
        }
    }

    private record ChunkCoordinate(int x, int z) {
        long key() {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }

        double distanceSquared(double blockX, double blockZ) {
            double dx = x * 16D + 8D - blockX;
            double dz = z * 16D + 8D - blockZ;
            return dx * dx + dz * dz;
        }
    }

    private record SnapshotTerrain(
            Map<Long, ChunkSnapshot> snapshots,
            int minimumY,
            int maximumY) implements SnapshotPathSolver.Terrain {
        @Override
        public Material blockType(int x, int y, int z) {
            if (y < minimumY || y >= maximumY) return null;
            int chunkX = Math.floorDiv(x, 16);
            int chunkZ = Math.floorDiv(z, 16);
            ChunkSnapshot snapshot = snapshots.get(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL));
            if (snapshot == null) return null;
            return snapshot.getBlockType(Math.floorMod(x, 16), y, Math.floorMod(z, 16));
        }
    }

    private static final class SnapshotWorkQueue {
        private static WorkloadRunnable runner;

        private SnapshotWorkQueue() {
        }

        private static void enqueue(Workload workload) {
            if (runner == null) {
                runner = new WorkloadRunnable(0.04D, SnapshotWorkQueue::finished);
                runner.addWorkload(workload);
                runner.startSync();
                return;
            }
            runner.addWorkload(workload);
        }

        private static void finished() {
            runner = null;
        }
    }

    private static final class AsyncSearchQueue {
        private static final int MAX_CONCURRENT_SEARCHES = Math.max(
                1,
                Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
        private static final Deque<SearchWork> PENDING = new ArrayDeque<>();
        private static int active;

        private AsyncSearchQueue() {
        }

        private static synchronized void submit(Plugin plugin, Runnable search) {
            PENDING.addLast(new SearchWork(plugin, search));
            dispatch();
        }

        private static synchronized void dispatch() {
            while (active < MAX_CONCURRENT_SEARCHES && !PENDING.isEmpty()) {
                SearchWork work = PENDING.removeFirst();
                active++;
                work.plugin().getServer().getScheduler().runTaskAsynchronously(work.plugin(), () -> {
                    try {
                        work.search().run();
                    } finally {
                        complete();
                    }
                });
            }
        }

        private static synchronized void complete() {
            active--;
            dispatch();
        }

        private record SearchWork(Plugin plugin, Runnable search) {
        }
    }
}
