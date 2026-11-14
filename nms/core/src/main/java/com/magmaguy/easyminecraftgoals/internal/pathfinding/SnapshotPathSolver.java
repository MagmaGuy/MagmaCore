package com.magmaguy.easyminecraftgoals.internal.pathfinding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

/** Pure A* search over immutable chunk snapshots. No Bukkit world state is read here. */
final class SnapshotPathSolver {
    private static final int[][] HORIZONTAL_DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final int[] GROUND_Y_OFFSETS = {0, 1, -1, -2, -3};

    private SnapshotPathSolver() {
    }

    static Result solve(
            Terrain terrain,
            Point requestedStart,
            Point requestedGoal,
            BodyProfile body,
            int maximumVisitedNodes) {
        return solve(terrain, requestedStart, requestedGoal, body, maximumVisitedNodes, () -> false);
    }

    static Result solve(Terrain terrain, Point requestedStart, Point requestedGoal, BodyProfile body,
                        int maximumVisitedNodes, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) return Result.noPath(false);
        Point start = nearestValid(terrain, requestedStart, body, 2, 8, cancelled);
        if (start == null) return Result.noPath(false);
        Point goal = nearestValid(terrain, requestedGoal, body, 8, 48, cancelled);
        if (goal == null) return Result.noPath(false);
        if (start.equals(goal)) return new Result(List.of(start), false);

        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(SearchNode::score));
        // One record per reached point holds its best distance, predecessor and closed flag, so a
        // neighbour costs one lookup and no boxed distance.
        Map<Point, NodeState> nodes = new HashMap<>();
        nodes.put(start, new NodeState(0D, null));
        open.add(new SearchNode(start, heuristic(start, goal)));

        int visited = 0;
        while (!open.isEmpty()) {
            if (cancelled.getAsBoolean()) return Result.noPath(false);
            SearchNode next = open.poll();
            Point current = next.point();
            NodeState currentState = nodes.get(current);
            if (currentState.closed) continue;
            currentState.closed = true;
            if (++visited > maximumVisitedNodes) return Result.noPath(true);
            if (current.equals(goal)) return new Result(reconstruct(nodes, current), false);

            for (Point neighbour : neighbours(terrain, current, body)) {
                NodeState neighbourState = nodes.get(neighbour);
                if (neighbourState != null && neighbourState.closed) continue;
                double candidateDistance = currentState.distance + movementCost(terrain, current, neighbour, body);
                double knownDistance = neighbourState == null ? Double.POSITIVE_INFINITY : neighbourState.distance;
                if (candidateDistance + 1.0E-9D >= knownDistance) {
                    continue;
                }
                if (neighbourState == null) {
                    nodes.put(neighbour, new NodeState(candidateDistance, current));
                } else {
                    neighbourState.previous = current;
                    neighbourState.distance = candidateDistance;
                }
                open.add(new SearchNode(neighbour, candidateDistance + heuristic(neighbour, goal)));
            }
        }
        return Result.noPath(false);
    }

    private static List<Point> neighbours(Terrain terrain, Point current, BodyProfile body) {
        return switch (body.mode()) {
            case GROUND -> groundNeighbours(terrain, current, body);
            case FLYING, AQUATIC -> volumeNeighbours(terrain, current, body);
        };
    }

    private static List<Point> groundNeighbours(Terrain terrain, Point current, BodyProfile body) {
        List<Point> neighbours = new ArrayList<>(10);
        // The four cardinal directions come first; a diagonal re-checks two of them, and the
        // terrain is an immutable snapshot, so their results are kept for the diagonals.
        Point east = null, west = null, south = null, north = null;
        for (int[] direction : HORIZONTAL_DIRECTIONS) {
            int dx = direction[0];
            int dz = direction[1];
            boolean diagonal = dx != 0 && dz != 0;
            Point candidate = groundTransition(terrain, current, dx, dz, body);
            if (!diagonal) {
                if (dx == 1) east = candidate;
                else if (dx == -1) west = candidate;
                else if (dz == 1) south = candidate;
                else north = candidate;
            }
            if (candidate == null) continue;
            if (diagonal
                    && ((dx == 1 ? east : west) == null
                    || (dz == 1 ? south : north) == null)) {
                continue;
            }
            neighbours.add(candidate);
        }

        BlockProperties feet = terrain.blockProperties(current.x(), current.y(), current.z());
        if (isWater(feet)) {
            Point above = current.add(0, 1, 0);
            Point below = current.add(0, -1, 0);
            if (isValid(terrain, above, body)) neighbours.add(above);
            if (isValid(terrain, below, body)) neighbours.add(below);
        }
        return neighbours;
    }

    private static Point groundTransition(Terrain terrain, Point current, int dx, int dz, BodyProfile body) {
        for (int offset : GROUND_Y_OFFSETS) {
            Point candidate = current.add(dx, offset, dz);
            if (isValid(terrain, candidate, body)) return candidate;
        }
        return null;
    }

    private static List<Point> volumeNeighbours(Terrain terrain, Point current, BodyProfile body) {
        List<Point> neighbours = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    Point candidate = current.add(dx, dy, dz);
                    if (isValid(terrain, candidate, body)) neighbours.add(candidate);
                }
            }
        }
        return neighbours;
    }

    private static Point nearestValid(
            Terrain terrain,
            Point requested,
            BodyProfile body,
            int horizontalRadius,
            int verticalRadius,
            BooleanSupplier cancelled) {
        if (isValid(terrain, requested, body)) return requested;
        Point best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                if (cancelled.getAsBoolean()) return null;
                for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                    Point candidate = requested.add(dx, dy, dz);
                    double distance = dx * dx + dz * dz + dy * dy * 0.25D;
                    if (distance >= bestDistance || !isValid(terrain, candidate, body)) continue;
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static boolean isValid(Terrain terrain, Point point, BodyProfile body) {
        if (point.y() < terrain.minimumY() || point.y() + body.heightBlocks() > terrain.maximumY()) return false;
        return switch (body.mode()) {
            case GROUND -> hasGroundClearance(terrain, point, body);
            case FLYING -> hasVolumeClearance(terrain, point, body, false);
            case AQUATIC -> hasVolumeClearance(terrain, point, body, true);
        };
    }

    private static boolean hasGroundClearance(Terrain terrain, Point point, BodyProfile body) {
        boolean inWater = false;
        for (int dx = -body.horizontalRadius(); dx <= body.horizontalRadius(); dx++) {
            for (int dz = -body.horizontalRadius(); dz <= body.horizontalRadius(); dz++) {
                BlockProperties feet = terrain.blockProperties(point.x() + dx, point.y(), point.z() + dz);
                if (!isPassable(feet)) return false;
                inWater |= isWater(feet);
                for (int height = 1; height < body.heightBlocks(); height++) {
                    if (!isPassable(terrain.blockProperties(
                            point.x() + dx,
                            point.y() + height,
                            point.z() + dz))) {
                        return false;
                    }
                }
                BlockProperties support = terrain.blockProperties(
                        point.x() + dx,
                        point.y() - 1,
                        point.z() + dz);
                if (!isSupport(support) && !isWater(feet)) return false;
            }
        }
        return inWater || !isHazard(terrain.blockProperties(point.x(), point.y() - 1, point.z()));
    }

    private static boolean hasVolumeClearance(
            Terrain terrain,
            Point point,
            BodyProfile body,
            boolean requireWater) {
        for (int dx = -body.horizontalRadius(); dx <= body.horizontalRadius(); dx++) {
            for (int dz = -body.horizontalRadius(); dz <= body.horizontalRadius(); dz++) {
                for (int height = 0; height < body.heightBlocks(); height++) {
                    BlockProperties block = terrain.blockProperties(
                            point.x() + dx,
                            point.y() + height,
                            point.z() + dz);
                    if (requireWater ? !isWater(block) : !isPassable(block)) return false;
                }
            }
        }
        return true;
    }

    private static double movementCost(Terrain terrain, Point from, Point to, BodyProfile body) {
        int dx = Math.abs(to.x() - from.x());
        int dy = Math.abs(to.y() - from.y());
        int dz = Math.abs(to.z() - from.z());
        double cost = Math.sqrt(dx * dx + dy * dy + dz * dz);
        BlockProperties feet = terrain.blockProperties(to.x(), to.y(), to.z());
        if (isWater(feet) && body.mode() == MovementMode.GROUND) cost += 3D;
        if (isWoodenDoor(feet)) cost += 2D;
        if (to.y() > from.y()) cost += 0.35D;
        return cost;
    }

    private static double heuristic(Point from, Point to) {
        int dx = to.x() - from.x();
        int dy = to.y() - from.y();
        int dz = to.z() - from.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<Point> reconstruct(Map<Point, NodeState> nodes, Point end) {
        List<Point> reversed = new ArrayList<>();
        Point current = end;
        while (current != null) {
            reversed.add(current);
            current = nodes.get(current).previous;
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static boolean isPassable(BlockProperties block) {
        return block != null && block.passable();
    }

    private static boolean isSupport(BlockProperties block) {
        return block != null && block.support();
    }

    private static boolean isWater(BlockProperties block) {
        return block != null && block.water();
    }

    private static boolean isWoodenDoor(BlockProperties block) {
        return block != null && block.woodenDoor();
    }

    private static boolean isHazard(BlockProperties block) {
        return block == null || block.hazard();
    }

    interface Terrain {
        BlockProperties blockProperties(int x, int y, int z);

        int minimumY();

        int maximumY();
    }

    /** Immutable block semantics captured on the server thread before A* runs asynchronously. */
    record BlockProperties(
            boolean passable,
            boolean support,
            boolean water,
            boolean woodenDoor,
            boolean hazard) {
        static final BlockProperties UNKNOWN = new BlockProperties(false, false, false, false, true);
    }

    enum MovementMode {
        GROUND,
        FLYING,
        AQUATIC
    }

    record BodyProfile(MovementMode mode, int horizontalRadius, int heightBlocks) {
        BodyProfile {
            if (horizontalRadius < 0) throw new IllegalArgumentException("horizontalRadius must not be negative");
            if (heightBlocks < 1) throw new IllegalArgumentException("heightBlocks must be positive");
        }
    }

    record Point(int x, int y, int z) {
        Point add(int dx, int dy, int dz) {
            return new Point(x + dx, y + dy, z + dz);
        }
    }

    record Result(List<Point> points, boolean exhaustedNodeBudget) {
        Result {
            points = List.copyOf(points);
        }

        static Result noPath(boolean exhaustedNodeBudget) {
            return new Result(List.of(), exhaustedNodeBudget);
        }

        boolean found() {
            return !points.isEmpty();
        }
    }

    private record SearchNode(Point point, double score) {
    }

    private static final class NodeState {
        private double distance;
        private Point previous;
        private boolean closed;

        private NodeState(double distance, Point previous) {
            this.distance = distance;
            this.previous = previous;
        }
    }
}
